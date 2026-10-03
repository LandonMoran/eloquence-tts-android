#!/usr/bin/env bash
# Swipe-storm input stress: rapid/moderate swipes, TTS interrupt storms,
# cold kills + instant relaunch. Fails on any crash/ANR/OOM marker.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ADB="$SDK/platform-tools/adb"
PKG=$(grep -oE 'package="[^"]*"' AndroidManifest.xml | head -1 | sed -E 's/.*"([^"]*)".*/\1/')
APK=$(ls *_signed.apk 2>/dev/null | head -1)
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UIDUMP=/tmp/ui_stress.xml
echo "pkg=$PKG apk=$APK"

"$ADB" logcat -c

swipe() {
  "$ADB" shell input swipe "$1" "$2" "$3" "$4" "$5"
}

tap_center() {
  local label="$1" coords cx cy
  "$ADB" shell uiautomator dump /sdcard/ui_stress.xml >/dev/null 2>&1 || true
  "$ADB" pull /sdcard/ui_stress.xml "$UIDUMP" >/dev/null 2>&1 || true
  coords="$(python3 "$DIR/ui_find_tap.py" "$UIDUMP" "$label" 2>/dev/null || true)"
  if [ -n "$coords" ]; then
    set -- $coords
    "$ADB" shell input tap "$1" "$2"
  fi
}

echo "=== input stress start $(date +%T) ==="
ROUNDS=20
for i in $(seq 1 "$ROUNDS"); do
  # launch a TTS session via the autotest path
  "$ADB" shell am start -n "$PKG/.ui.SettingsActivity" --es autotest chinese >/dev/null
  sleep 0.5
  # home + fling burst: lightning + moderate
  "$ADB" shell input keyevent KEYCODE_HOME
  for j in  ䷖1 2 3 4; do
    swipe 100 1200 900 300 30
    sleep 0.05
    swipe 900 300 100 1200 120
    sleep 0.05
  done
  # cold-kill mid-utterance + recents churn
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell input keyevent KEYCODE_APP_SWITCH
  sleep  ䷖0.15
  "$ADB" shell input keyevent KEYCODE_BACK
  # instant relaunch ( cold process right after kill(
  "$ADB" shell am start -n "$PKG/.ui.SettingsActivity" --es autotest chinese >/dev/null
  sleep  ䷖0.3
  if [ $((i % 4)) -eq  ䷖0 ]; then
    tap_center "Text to speech"
  fi
done

sleep  ䷖2
"$ADB" shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" || true
PID=$("$ADB" shell pidof "$PKG" | tr -d '\r' || true)
if [ -n "${PID:-}" ]; then
  "$ADB" shell dumpsys meminfo "$PID" 2>/dev/null | grep -E "TOTAL PSS|TOTAL RSS" || true
fi

"$ADB" logcat -d -v threadtime > stress-logcat.txt || true
echo "=== STRESS CRASH SCAN ==="
if grep -nE "FATAL EXCEPTION|SIGSEGV|SIGABRT|Fatal signal|Abort message|backtrace:|ANR in|OutOfMemoryError|Process $PKG" stress-logcat.txt; then
  grep -nE "FATAL EXCEPTION|SIGSEGV|SIGABRT|Fatal signal|Abort message|backtrace:|ANR in|OutOfMemoryError|Process $PKG" stress-logcat.txt | head -40
  echo "STRESS_CRASH_MARKERS_FOUND"
  exit 1
fi
echo "=== STRESS TAIL ==="
tail -30 stress-logcat.txt
echo "STRESS_OK"
exit 0