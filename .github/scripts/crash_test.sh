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
sleep 120
"$ADB" shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" || true
"$ADB" logcat -d -v threadtime > logcat-full.txt || true
echo "=== CHS_ORACLE / CRASHHOOK DIAG LINES ===="
grep -nE "CHS_ORACLE|CRASHHOOK" logcat-full.txt | head -60 || true
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