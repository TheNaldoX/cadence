# Obtenir le fichier `.apk`

Trois chemins. Le premier ne demande **rien à installer** sur ton ordinateur.

---

## Chemin A — GitHub compile à ta place (recommandé, ~6 minutes)

Le projet contient déjà tout le nécessaire : `.github/workflows/build-apk.yml`.
GitHub fournit gratuitement le JDK, le SDK Android et Gradle.

1. Va sur **github.com** → bouton vert **New** → nomme le dépôt `cadence` →
   **Create repository**.
2. Sur la page qui s'affiche, clique **uploading an existing file**.
3. Décompresse l'archive `cadence-android.zip`, puis **glisse le contenu du
   dossier `cadence`** (pas le dossier lui-même) dans la zone de dépôt.
   Attends la fin de l'envoi, puis **Commit changes**.
4. Onglet **Actions** → le workflow « APK » démarre tout seul. Compte 4 à
   6 minutes la première fois.
5. Une fois la coche verte affichée, deux façons de récupérer le fichier :
   - **Depuis l'ordinateur** : dans la page du run, section *Artifacts*,
     télécharge `cadence-apk`.
   - **Depuis le téléphone**, plus pratique : onglet **Releases** → version
     *Cadence — dernier APK de debug* → télécharge `cadence-debug.apk`.
     Android demandera d'autoriser l'installation depuis cette source.

> ⚠️ Un point à surveiller à l'étape 3 : GitHub ignore les dossiers commençant
> par un point lors d'un glisser-déposer depuis certains navigateurs. Si
> l'onglet Actions reste vide, c'est que `.github/` n'a pas été envoyé. Dans ce
> cas, crée le fichier à la main : **Add file → Create new file**, nomme-le
> `.github/workflows/build-apk.yml` et colle le contenu du fichier
> correspondant de l'archive.

Ensuite, à chaque modification du code que tu envoies sur GitHub, un nouvel APK
est produit automatiquement.

### Et je peux te le ramener ici

J'ai vérifié : depuis mon environnement, je n'atteins ni les dépôts Maven ni
l'API GitHub, **mais je peux télécharger les fichiers publiés dans les
releases GitHub** (testé, 200 OK).

Donc si tu rends le dépôt **public** à l'étape 1, il te suffit de me donner son
adresse une fois la coche verte affichée — par exemple
`https://github.com/victor/cadence` — et je récupère l'APK pour te le déposer
directement dans la conversation. Tu n'auras rien d'autre à faire que le
transférer sur ton téléphone.

Si tu préfères garder le dépôt privé, le chemin reste valable, mais c'est toi
qui télécharges le fichier depuis l'onglet Releases.

---

## Chemin B — une seule commande sur ton ordinateur (Linux ou macOS)

```bash
cd cadence
./build-apk.sh
```

Le script vérifie Java, installe le SDK Android dans `~/Android/sdk` et Gradle
dans `~/.cadence-build`, puis compile. Il ne touche à rien d'autre sur le
système. Résultat : `cadence-debug.apk` à la racine du projet.

Pré-requis : un **JDK 17 ou plus récent**, environ 4 Go de disque libre.

```bash
# Debian / Ubuntu
sudo apt install openjdk-17-jdk unzip curl
# macOS
brew install --cask temurin@17
```

Pour l'envoyer sur le téléphone branché en USB, mode développeur activé :

```bash
~/Android/sdk/platform-tools/adb install -r cadence-debug.apk
```

---

## Chemin C — Android Studio (tous systèmes, y compris Windows)

1. Installe **Android Studio** (Ladybug 2024.2 ou plus récent).
2. *File* → *Open* → sélectionne le dossier `cadence`.
3. Laisse la synchronisation Gradle se faire ; accepte les mises à jour
   d'AGP ou de Kotlin si elles sont proposées.
4. *Build* → *Build App Bundle(s) / APK(s)* → **Build APK(s)**.
5. La notification « locate » ouvre `app/build/outputs/apk/debug/`.

---

## Installer l'APK sur le téléphone

C'est un APK de **debug**, signé avec la clé de debug d'Android. Il s'installe
sans souci, mais Android le considère comme venant d'une source inconnue :

1. Ouvre le fichier depuis le gestionnaire de fichiers ou le navigateur.
2. Android propose d'autoriser l'installation pour cette application-là.
   Accepte, puis reviens à l'installation.
3. Au premier lancement, accorde la **localisation précise** et les
   **notifications** : sans elles, l'enregistrement d'une sortie ne peut pas
   fonctionner.

Pour une diffusion réelle (Play Store ou distribution directe), il faudra une
clé de signature de release. Dis-le-moi si tu en arrives là, c'est une
vingtaine de lignes à ajouter au `build.gradle.kts`.

---

## Si la compilation échoue

Le projet a été vérifié au compilateur Kotlin (3 137 lignes, 0 erreur), mais
les couches Compose, Room et MapLibre n'ont jamais pu être compilées ici faute
d'accès aux dépôts Maven. Une erreur résiduelle sur ces couches reste donc
possible.

Dans ce cas, envoie-moi le message d'erreur complet — celui qui commence par
`e: file:///...` pour Kotlin, ou le bloc `* What went wrong:` pour Gradle. La
cause la plus probable est une version d'artefact introuvable : elles se
changent toutes dans `gradle/libs.versions.toml`, en une ligne.

Le point le plus susceptible de coincer est **MapLibre**. J'ai fixé
`maplibre = "11.5.2"` ; la dernière version publiée est `13.5.0`. Si Gradle ne
trouve pas la 11.5.2, remplace la ligne. Le passage à la branche 13 peut
demander de retoucher `ui/map/CadenceMap.kt`, seul fichier à utiliser MapLibre.
