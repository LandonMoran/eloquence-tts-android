set -eu
echo "=== smoke.sh trace start $(date +%T) ==="
for v in GH_TOKEN GITHUB_TOKEN GITHUB_REF GITHUB_WORKSPACE GITHUB_SHA; do
  if test -n "${!v:-}"; then
    echo "$v: SET"
  else
    echo "$v: EMPTY"
  fi
done
echo "=== smoke.sh first gh call ==="
set -x
cd "$GITHUB_WORKSPACE"
# --- fetch APK from latest successful build-apk run ---
for i in $(seq 1 60); do
  R=$(gh run list --workflow build-apk.yml --branch "${GITHUB_REF#refs/heads/}" --limit 1 --json databaseId --jq '.[0].databaseId // empty')
  [ -n "$R" ] && break
  sleep 10
done
echo "latest build-apk run: $R"
for i in $(seq 1 180); do
  [ -n "$R" ] || break
  C=$(gh run view "$R" --json status,conclusion --jq 'if .status=="completed" then .conclusion else "in_progress" end')
  [ "$C" = "success" ] && break
  [ "$C" != "in_progress" ] && { echo "latest build-apk run concluded: $C"; R=""; break; }
  sleep 10
done
if [ -z "$R" ]; then
  R=$(gh run list --workflow build-apk.yml --branch "${GITHUB_REF#refs/heads/}" --limit 50 --json databaseId,conclusion --jq '. | map(select(.conclusion=="success") | .databaseId) | .[0] // empty')
fi
[ -n "$R" ] || { echo "no successful build-apk run found"; exit 1; }
echo "using build-apk run: $R"
A=""
for i in $(seq 1 30); do
  A=$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/$R/artifacts" | python3 -c 'import json,sys; d=json.load(sys.stdin); a=[x["id"] for x in d.get("artifacts",[]) if x.get("name","").startswith(("vvttts", "vvtts"))and not x.get("expired",False)]; print(a[0] if a else "")' || true)
  [ -n "$A" ] && break
  sleep 10
done
[ -n "$A" ] || { echo "no vvttts artifact on run $R"; exit 1; }
echo "artifact id: $A"
mkdir -p /tmp/apk && cd /tmp/apk
gh api -H "Accept: application/vnd.github+json" "repos/${GITHUB_REPOSITORY}/actions/artifacts/$A/zip" > art.zip
unzip -q -o art.zip
APK=$(find . -name "*.apk" | head -1)
echo "APK=$APK" | tee /tmp/apk/path.env
ls -la
# --- install, launch, verify ---
adb install -r "$APK"
adb shell am start -W -n com.xw.vvttts/.ui.SettingsActivity
sleep 6
echo "=== resumed activity ==="
adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -3
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
echo "=== settings labels in a11y tree ==="
grep -o 'text="[^"]*"' /tmp/ui.xml | sort -u | head -60
echo "=== crash scan ==="
if adb logcat -d | grep -iE 'FATAL EXCEPTION|StackOverflowError|ANR in com.xw.vvttts'; then
  echo "CRASH DETECTED"
  adb logcat -d | grep -iE 'FATAL EXCEPTION|StackOverflowError|ANR in com.xw.vvttts' | tail -20
  exit 1
fi
echo "SMOKE_OK"

