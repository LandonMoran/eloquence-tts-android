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
echo "=== FATAL / CRASH LINES ===="
if grep -nE "FATAL EXCEPTION|SIGSEGV|SIGABRT|Fatal signal|Abort message|backtrace:|Process $PKG" logcat-full.txt;then
  grep -nE "FATAL EXCEPTION|SIGSEGV|SIGABRT|Fatal signal|Abort message|backtrace:|Process $PKG" logcat-full.txt | head -40
  echo "CRASH_MARKERS_FOUND"
  exit 1
else
  echo "NO_CRASH_MARKERS"
fi
echo "=== TAIL ===="
tail -25 logcat-full.txt