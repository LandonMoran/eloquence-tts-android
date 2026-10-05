#!/usr/bin/env bash
# Run after compiling the application (build.sh), or point TTS_CLASSES at equivalent classes.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${ANDROID_JAR:?Set ANDROID_JAR to platforms/android-34/android.jar}"
classes="${TTS_CLASSES:-out_classes}"
test_out="$(mktemp -d)"
trap 'rm -rf "$test_out"' EXIT
classpath="$classes:libs/kotlin-stdlib-1.9.25.jar:$ANDROID_JAR"
mapfile -t sources < <(rg --files tests/tts-lifecycle -g '*.java')
javac -cp "$classpath" -d "$test_out" "${sources[@]}"
# Stubs precede android.jar; production application classes run unchanged.
timeout 20s java -cp "$test_out:$classpath" com.xw.vvtts.services.LifecycleTest
