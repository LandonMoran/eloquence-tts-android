#!/usr/bin/env bash
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ADB="$SDK/platform-tools/adb"
# Gradle migration moved the app id from the manifest's package= to build.gradle
# namespace; derive it there, falling back to the legacy manifest attr.
PKG=$(grep -oE "namespace[[:space:]]+['\"][^'\"]+['\"]" build.gradle | head -1 | sed -E "s/.*['\"]([^'\"]+)['\"].*/\1/")
[ -n "$PKG" ] || PKG=$(grep -oE 'package="[^"]*"' AndroidManifest.xml | head -1 | sed -E 's/.*"([^"]*)".*/\1/')
# Workflow lanes stage the built APK as ./vvtts_signed.apk (cp from build/outputs).
APK=$(ls ./vvtts_signed.apk 2>/dev/null | head -1)
echo "pkg=$PKG apk=$APK"

"$ADB" install -r "$APK"
# The app targets API 33+, and the google_apis emulator image shows a
# POST_NOTIFICATIONS grant dialog on first launch. That dialog steals
# foreground and starves the SettingsActivity autotest-synthesis path, so the
# lane used to fail with a false NO_SYNTH_SAMPLES. Pre-grant before starting.
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/.ui.SettingsActivity" --es autotest german

# Two spaced native snapshots of the elq-synth worker. Identical frames across
# the two = wedged tight loop (real hang); changed frames = slow-but-progressing
# compute that is merely beating the lane's time budget on the emulator. This is
# the ONLY way to tell a true non-terminating delta_rule loop from a bounded
# million-rule generation that outruns ~120s on a software-rendered emulator.
APP_PID=$(
	# Cold app spawn can take several seconds right after install; poll until
	# the process exists or we give up so we never skip the two-snapshot probe
	# for a merely-slow first launch.
	for _ in $(seq 1 30); do
		p=$("$ADB" shell pidof -s "$PKG" | tr -d '\r' || true)
		[ -n "$p" ] && { echo "$p"; break; }
		sleep 2
	done
)
take_snap() {
	local out="$1"
	"$ADB" root >/dev/null 2>&1 || true
	sleep 2
	"$ADB" shell "kill -3 ${APP_PID}" >/dev/null 2>&1 || true
	sleep 6
	local f
	f=$("$ADB" shell "ls -t /data/anr/ 2>/dev/null | head -1" | tr -d '\r' || true)
	if [ -n "${f:-}" ]; then
		"$ADB" shell "cat /data/anr/${f}" > "$out" 2>&1 || true
	fi
}
if [ -n "${APP_PID:-}" ]; then
	echo "=== NATIVE SNAPSHOT A pid=$APP_PID ==="
	take_snap native-stacks-a.txt
	sleep 25
	echo "=== NATIVE SNAPSHOT B pid=$APP_PID ==="
	take_snap native-stacks-b.txt
	# Delimit the elq-synth block at the next thread header, then keep only
	# libvvtts_core native frames so system frames cannot mask the verdict.
	extract_elq() {
		awk '/"elq-synth"/{f=1} f&&/native:/&&/libvvtts_core\.so/{print} f&&/^"[^"]+" /&&!/elq-synth/{exit}' "$1" 2>/dev/null | sed -E 's/0x[0-9a-f]+/<pc>/g'
	}
	extract_elq native-stacks-a.txt > /tmp/elq-a.txt || true
	extract_elq native-stacks-b.txt > /tmp/elq-b.txt || true
	if [ -s /tmp/elq-a.txt ] && [ -s /tmp/elq-b.txt ]; then
		# Written beside the snapshots (not /tmp) so the workflow's artifact upload
		# carries the verdict evidence with the dumps it came from.
		if diff -u /tmp/elq-a.txt /tmp/elq-b.txt > elq-diff.txt 2>&1; then
			echo "ELQ_VERDICT:WEDGED_FROZEN_FRAMES (identical native frames both snapshots = non-terminating loop)"
		else
			echo "ELQ_VERDICT:CHANGING_FRAMES (elq-synth advanced between snapshots = slow-but-progressing)"
			echo "--- frame set A ---"; cat /tmp/elq-a.txt
			echo "--- frame set B ---"; cat /tmp/elq-b.txt
		fi
	else
		echo "ELQ_VERDICT:NO_CLEAN_ELQ_BLOCK (missing app native frames in one or both anr dumps)"
		[ ! -s /tmp/elq-a.txt ] && echo "(block A empty)"
		[ ! -s /tmp/elq-b.txt ] && echo "(block B empty)"
	fi
else
	echo "no app pid found for native dump"
fi
sleep 100
"$ADB" shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" || true
"$ADB" logcat -d -v threadtime > logcat-full.txt || true
echo "=== SYNTHESIS / CRASHHOOK DIAG LINES ===="
grep -nE "RoleTest|SPD|CRASHHOOK" logcat-full.txt | head -60 || true
echo "=== APP-ENGINE LINES (EloquenceEngine / VvTts / vvtts( ===="
grep -nE " (EloquenceEngine|VvTts|VvttsCore|vvttts|AndroidRuntime):" logcat-full.txt | tail -60 || echo "no app-engine lines at all"
echo "=== FATAL / CRASH LINES ===="
if grep -nE "FATAL EXCEPTION|SIGSEGV|SIGABRT|Fatal signal|Abort message|backtrace:|Process $PKG" logcat-full.txt;then
	echo "CRASH_MARKERS_FOUND" && exit 1
fi
echo "=== TAIL 40 ===="
tail -40 logcat-full.txt
# Main-process test-button path (autotest german -> testGerman) synthesizes directly in
# SettingsActivity and reports PCM size via RoleTest. The SPD.*synth=.*pcm= line is emitted
# only by VvTtsService in the separate :tts process, which this lane never starts — so gate
# on the RoleTest audio-count line the driven path actually produces.
if ! grep -qE "RoleTest.*total=[1-9][0-9]*" logcat-full.txt;then
	# Dump whatever the synthesis path actually logged, then fail.
	grep -nE "RoleTest.*total|SPD.*(synth|pcm|samples)" logcat-full.txt | head -20 || echo "no RoleTest synthesis line at all"
	echo "NO_SYNTH_SAMPLES - the triggered synthesis produced no audio; failing"
	exit 1
fi
echo "NO_CRASH_MARKERS"