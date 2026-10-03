#!/usr/bin/env bash
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ADB="$SDK/platform-tools/adb"
PKG=$(grep -oE 'package="[^"]*"' AndroidManifest.xml | head -1 | sed -E 's/.*"([^"]*)".*/\1/')
APK=$(ls *_signed.apk 2>/dev/null | head -1)
echo "pkg=$PKG apk=$APK"

"$ADB" install -r "$APK"
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/.ui.SettingsActivity" --es autotest chinese
# Native stack snapshot while elq-synth is ( presumably) parked in nativeInitEngine.
sleep 20
APP_PID=$("$ADB" shell pidof -s "$PKG" | tr -d '\r' || true)
if [ -n "${APP_PID:-}" ]; then
	echo "=== NATIVE STACKS pid=$APP_PID ==="
	"$ADB" root >/dev/null 2>&1 || true
	sleep 2
	"$ADB" shell "kill -3 ${APP_PID}" >/dev/null 2>&1 || true
	sleep 6
	ANR_FILE=$("$ADB" shell "ls -t /data/anr/ 2>/dev/null | head -1" | tr -d '\r' || true)
	if [ -n "${ANR_FILE:-}" ]; then
		"$ADB" shell "cat /data/anr/${ANR_FILE}" > native-stacks.txt 2>&1 || true
	fi
	if ! grep -aq 'native:' native-stacks.txt 2>/dev/null; then
		"$ADB" shell "for t in /proc/${APP_PID}/task/*; do tid=\${t##*/}; debuggerd -b \$tid; done" > native-stacks.txt 2>&1 || true
	fi
	grep -aA45 '"elq-synth"' native-stacks.txt | grep -aE 'native:|at |"elq-synth"' | head -120 || true
else
	echo "no app pid found for native dump"
fi
sleep 100
"$ADB" shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" || true
"$ADB" logcat -d -v threadtime > logcat-full.txt || true
echo "=== CHS_ORACLE / CRASHHOOK DIAG LINES ===="
grep -nE "CHS_ORACLE|CRASHHOOK" logcat-full.txt | head -60 || true
echo "=== APP-ENGINE LINES (EloquenceEngine / VvTts / vvtts( ==="
grep -nE " (EloquenceEngine|VvTts|VvttsCore|vvttts|AndroidRuntime):" logcat-full.txt | tail -60 || echo "no app-engine lines at all"
echo "=== FATAL / CRASH LINES ===="
if grep -nE "FATAL EXCEPTION|SIGSEGV|SIGABRT|Fatal signal|Abort message|backtrace:|Process $PKG" logcat-full.txt;then
	echo "CRASH_MARKERS_FOUND" && exit 1
fi
echo "=== TAIL 40 ===="
tail -40 logcat-full.txt
if ! grep -qE "CHS_ORACLE.*build_pcm samples=[1-9][0-9]*" logcat-full.txt;then
	# Dump whatever the synthesis actually logged, then fail.
	grep -nE "CHS_ORACLE.*samples" logcat-full.txt | head -20 || echo "no CHS_ORACLE samples line at all"
	echo "NO_SYNTH_SAMPLES - zh synthesis produced zero audio;failing"
	exit 1
fi
echo "NO_CRASH_MARKERS"