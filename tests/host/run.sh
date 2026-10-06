#!/usr/bin/env bash
# Host source/regression checks; no Android SDK/NDK build.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${ANDROID_JAR:?Android API jar required}"
: "${KOTLINC_CP:?Kotlin 1.9.25 compiler classpath required}"
: "${KXML_JAR:?kxml2 2.3.0 jar required for host XML fixture}"
test_out="$(mktemp -d)"
trap 'rm -rf "$test_out"' EXIT
mkdir -p "$test_out/production" "$test_out/stubs" "$test_out/tests"
python3 tests/host/generate_r.py "$test_out/R.java"
javac -d "$test_out/production" "$test_out/R.java"
libs="$(echo libs/*.jar | tr ' ' ':')"
java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 \
  -classpath "$ANDROID_JAR:$test_out/production:$libs" -d "$test_out/production" $(rg --files src -g '*.kt')
javac -cp "$ANDROID_JAR:$KXML_JAR" -d "$test_out/stubs" $(rg --files tests/host/stubs -g '*.java') \
  tests/tts-lifecycle/stubs/android/util/Log.java tests/tts-lifecycle/stubs/android/os/SystemClock.java \
  tests/tts-lifecycle/stubs/android/speech/tts/TextToSpeechService.java
java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 \
  -Xfriend-paths="$test_out/production" -classpath "$test_out/stubs:$test_out/production:$ANDROID_JAR:$libs" \
  -d "$test_out/tests" tests/host/RegressionTest.kt
timeout 60s java -cp "$test_out/stubs:$test_out/tests:$test_out/production:$KXML_JAR:$ANDROID_JAR:$libs" com.xw.vvtts.tests.RegressionTestKt
mkdir -p "$test_out/update"
javac -cp "$ANDROID_JAR:$test_out/production:$libs" -d "$test_out/update" \
  $(rg --files tests/host/update -g '*.java')
timeout 20s java -cp "$test_out/update:$test_out/production:$ANDROID_JAR:$libs" com.xw.vvtts.tests.UpdateLifecycleTest
