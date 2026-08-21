#!/bin/sh
#
# Lanceur minimal du wrapper Gradle.
# Android Studio regenere la version officielle complete a la premiere
# synchronisation ; ce script suffit pour lancer une compilation en ligne de
# commande.
#
set -e
APP_HOME=$(cd "$(dirname "$0")" >/dev/null && pwd)

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

exec "$JAVACMD" \
    -Dorg.gradle.appname="$(basename "$0")" \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
