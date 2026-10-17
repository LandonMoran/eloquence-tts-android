#!/usr/bin/env bash
# tests/vendor-openevv-test.sh — regression tests for tools/vendor-openevv.sh,
# which BUMPS the native/openevv git submodule to upstream main (it no longer
# copies a flat tree or writes .openevv-pin; the submodule pointer is the pin).
#
# Each test builds a throwaway superproject holding the submodule at a known
# pin and runs the real tools/vendor-openevv.sh against it:
#   * preview (default) on an already-current submodule reports up to date,
#     moves no pointer, stages nothing;
#   * preview on an old pin lists the incoming commits and touches nothing;
#   * --apply bumps an old pin to upstream main and stages the gitlink;
#   * an uninitialized submodule aborts with a clear message.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REAL_SCRIPT="$ROOT/tools/vendor-openevv.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
pass() { echo "ok  : $*"; }
fail() { echo "FAIL: $*"; failures=$((failures+1)); }

# --- throwaway upstream openevv remote with two commit generations ---
UP="$(mktemp -d "$WORK/up.XXXX")"; rmdir "$UP"
git init -q --bare "$UP"
SRC="$(mktemp -d "$WORK/src.XXXX")"; rmdir "$SRC"
git clone -q "$UP" "$SRC"
git -C "$SRC" config user.name  "openevv bump test"
git -C "$SRC" config user.email "bump-test@example.com"
( cd "$SRC"
  git checkout -q -b main
  mkdir -p include src/port cli
  echo "engine-content v1" > include/eci.h
  echo "engine port_ctors" > src/port/port_ctors.c
  git add -A && git commit -qm "engine commit one"
  git push -q origin main
  echo "engine-content v2" > include/eci.h
  git add -A && git commit -qm "engine commit two"
  git push -q origin main
)
# default branch `main` (as GitHub-hosted openevv), not unborn `master`
git --git-dir="$UP" symbolic-ref HEAD refs/heads/main
OLD_SHA="$(git -C "$SRC" rev-parse HEAD~1)"
NEW_SHA="$(git -C "$SRC" rev-parse main)"

new_app() {  # fresh superproject set up with the submodule pinned to $1, clean index
  local d
  d="$(mktemp -d "$WORK/app.XXXX")"; rmdir "$d"
  mkdir -p "$d/tools"
  cp "$REAL_SCRIPT" "$d/tools/vendor-openevv.sh"
  git -C "$d" init -q -b main
  git -C "$d" config user.name  "openevv bump test"
  git -C "$d" config user.email "bump-test@example.com"
  git -C "$d" -c protocol.file.allow=always submodule add -q "$UP" native/openevv >/dev/null
  git -C "$d/native/openevv" fetch -q origin
  git -C "$d/native/openevv" checkout -q --detach "$1"
  git -C "$d" add native/openevv
  git -C "$d" commit -qm init   # clean index before running the script
  APP="$d"
}
pin()         { git -C "$APP/native/openevv" rev-parse HEAD; }
index_clean() { git -C "$APP" diff --cached --quiet; }

# ---- Test 1: preview on a current submodule = up to date, no-op ----
new_app "$NEW_SHA"
out=$(bash "$APP/tools/vendor-openevv.sh" 2>&1); rc=$?
[ "$rc" -eq 0 ] && pass "preview on current submodule exits 0" || fail "preview on current submodule exits 0 (rc=$rc)"
echo "$out" | grep -qi 'up to date' && pass "reports 'up to date'" || fail "reports 'up to date'"
[ "$(pin)" = "$NEW_SHA" ] && pass "pointer unchanged" || fail "pointer unchanged"
index_clean && pass "stages nothing" || fail "stages nothing"

# ---- Test 2: preview on an old pin lists delta, touches nothing ----
new_app "$OLD_SHA"
out=$(bash "$APP/tools/vendor-openevv.sh" 2>&1)
echo "$out" | grep -qi 'up to date' \
  && fail "old pin not misreported as up to date" || pass "old pin not misreported as up to date"
echo "$out" | grep -q 'incoming commits' \
  && pass "lists incoming commits" || fail "lists incoming commits"
[ "$(pin)" = "$OLD_SHA" ] && pass "leaves old pin untouched" || fail "leaves old pin untouched"
index_clean && pass "stages nothing" || fail "stages nothing"

# ---- Test 3: --apply bumps to main and stages the gitlink ----
out=$(bash "$APP/tools/vendor-openevv.sh" --apply 2>&1)
[ "$(pin)" = "$NEW_SHA" ] && pass "--apply moves pin to main" || fail "--apply moves pin to main"
echo "$out" | grep -qi 'applied' && pass "--apply reports applied" || fail "--apply reports applied"
index_clean && fail "--apply stages the gitlink" || pass "--apply stages the gitlink"
echo "$out" | grep -q 'chore(engine): bump' \
  && pass "suggests pointer-bump PR body" || fail "suggests pointer-bump PR body"

# ---- Test 4: uninitialized submodule aborts ----
rm -rf "$APP/native/openevv"
t4out="$(bash "$APP/tools/vendor-openevv.sh" 2>&1)"
{ echo "$t4out" | grep -q 'is not an initialized submodule'; } \
  && pass "uninitialized submodule aborts with message" \
  || fail "uninitialized submodule aborts with message"

echo
if [ "$failures" -gt 0 ]; then
  echo "vendor-openevv-test: $failures FAILURE(S)"
  exit 1
fi
echo "vendor-openevv-test: all checks passed"
exit 0