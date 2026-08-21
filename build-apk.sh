#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Compile l'APK de debug de Cadence sans Android Studio.
#
#   ./build-apk.sh
#
# Le script installe ce qui manque (SDK Android, Gradle) dans ton dossier
# personnel, sans toucher au reste du systeme, puis produit le fichier
# cadence-debug.apk a la racine du projet.
#
# Pre-requis : un JDK 17 ou plus recent, environ 4 Go de disque, et une
# connexion internet pour le premier lancement.
# Teste sur Linux et macOS. Sous Windows, utilise Android Studio (voir README).
# ---------------------------------------------------------------------------
set -euo pipefail

GRADLE_VERSION="8.9"
# Si cette adresse renvoie une erreur 404, prends l'adresse courante sur
# https://developer.android.com/studio#command-tools et remplace le numero.
CMDLINE_BUILD="11076708"

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS_DIR="${CADENCE_TOOLS_DIR:-$HOME/.cadence-build}"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/sdk}}"

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
info()  { printf '  %s\n' "$*"; }
die()   { printf '\033[31mErreur :\033[0m %s\n' "$*" >&2; exit 1; }

step=0
next() { step=$((step + 1)); printf '\n'; bold "[$step/5] $*"; }

# --- 1. Java ---------------------------------------------------------------
next "Vérification de Java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
else
  die "aucun JDK trouvé. Installe un JDK 17 (Temurin, Zulu, ou 'apt install openjdk-17-jdk')."
fi
JAVA_MAJOR="$("$JAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
[ "$JAVA_MAJOR" -ge 17 ] 2>/dev/null || die "Java $JAVA_MAJOR détecté, il en faut 17 ou plus."
info "Java $JAVA_MAJOR — $JAVA"

# --- 2. SDK Android --------------------------------------------------------
next "SDK Android"
case "$(uname -s)" in
  Linux)  CMDLINE_OS="linux" ;;
  Darwin) CMDLINE_OS="mac" ;;
  *) die "système non géré : $(uname -s). Utilise Android Studio." ;;
esac

SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  info "installation des outils en ligne de commande dans $SDK_DIR"
  mkdir -p "$SDK_DIR/cmdline-tools" "$TOOLS_DIR"
  ZIP="$TOOLS_DIR/commandlinetools-$CMDLINE_OS.zip"
  URL="https://dl.google.com/android/repository/commandlinetools-${CMDLINE_OS}-${CMDLINE_BUILD}_latest.zip"
  [ -f "$ZIP" ] || curl -fSL --progress-bar -o "$ZIP" "$URL" \
    || die "téléchargement impossible depuis $URL"
  rm -rf "$SDK_DIR/cmdline-tools/latest" "$TOOLS_DIR/unpacked"
  mkdir -p "$TOOLS_DIR/unpacked"
  unzip -q "$ZIP" -d "$TOOLS_DIR/unpacked"
  mv "$TOOLS_DIR/unpacked/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$TOOLS_DIR/unpacked"
else
  info "outils déjà présents"
fi

export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"

info "acceptation des licences"
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true

info "installation des composants (peut prendre quelques minutes)"
"$SDKMANAGER" --sdk_root="$SDK_DIR" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null

printf 'sdk.dir=%s\n' "$SDK_DIR" > "$PROJECT_DIR/local.properties"
info "local.properties écrit"

# --- 3. Gradle -------------------------------------------------------------
next "Gradle $GRADLE_VERSION"
GRADLE_HOME="$TOOLS_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  info "téléchargement de Gradle $GRADLE_VERSION"
  ZIP="$TOOLS_DIR/gradle-$GRADLE_VERSION-bin.zip"
  [ -f "$ZIP" ] || curl -fSL --progress-bar -o "$ZIP" \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
    || die "téléchargement de Gradle impossible"
  unzip -q "$ZIP" -d "$TOOLS_DIR"
else
  info "Gradle déjà présent"
fi

# --- 4. Compilation --------------------------------------------------------
next "Compilation de l'APK"
cd "$PROJECT_DIR"
"$GRADLE_BIN" assembleDebug --no-daemon --no-configuration-cache "$@"

# Regenere un wrapper officiel au passage : les lancements suivants pourront
# se faire avec ./gradlew, sans repasser par ce script.
"$GRADLE_BIN" wrapper --gradle-version "$GRADLE_VERSION" --quiet >/dev/null 2>&1 || true

# --- 5. Resultat -----------------------------------------------------------
next "Résultat"
APK="$(find app/build/outputs/apk/debug -name '*.apk' -print -quit 2>/dev/null || true)"
[ -n "$APK" ] || die "aucun APK produit. Relance avec ./build-apk.sh --stacktrace pour voir la cause."
cp "$APK" "$PROJECT_DIR/cadence-debug.apk"

printf '\n'
bold "APK prêt : $PROJECT_DIR/cadence-debug.apk"
info "taille : $(du -h "$PROJECT_DIR/cadence-debug.apk" | cut -f1)"
printf '\n'
info "Installation par câble USB, téléphone en mode développeur :"
info "  $SDK_DIR/platform-tools/adb install -r cadence-debug.apk"
info "Sinon, copie le fichier sur le téléphone et ouvre-le."
printf '\n'
