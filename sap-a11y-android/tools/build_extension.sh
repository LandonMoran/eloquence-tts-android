#!/usr/bin/env bash
# Compile the accessibility extension to dex and package it as the .mpe that
# the Morphe patch merges into the app.
#
# This is the SDK-only path so the port can be built without the Morphe Gradle
# plugin. The canonical build is the extension Gradle module + `buildAndroid`
# from the Morphe patches template; both produce the same classes.
set -eu
cd "$(dirname "$0")/.."

: "${ANDROID_HOME:=${ANDROID_SDK:-$HOME/tools/android/sdk}}"
JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
D8="$ANDROID_HOME/build-tools/35.0.0/d8"

[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR" >&2; exit 1; }
[ -x "$D8" ] || { echo "missing d8 at $D8" >&2; exit 1; }

rm -rf build/classes build/dex
mkdir -p build/classes build/dex artifacts/extensions

"$JAVA_HOME/bin/javac" \
    -source 11 -target 11 -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d build/classes \
    extension/src/main/java/app/sapa11y/extension/*.java

# shellcheck disable=SC2046
"$D8" --min-api 28 --lib "$ANDROID_JAR" --output build/dex \
    $(find build/classes -name '*.class')

cp build/dex/classes.dex artifacts/extensions/sapa11y.mpe
echo "extension: artifacts/extensions/sapa11y.mpe ($(stat -c%s artifacts/extensions/sapa11y.mpe) bytes)"
