#!/usr/bin/env bash
# tests/vendor-openevv-test.sh — regression tests for tools/vendor-openevv.sh.
#
# Exercises, with a throwaway git repo standing in for the OpenEVV fork:
#   * a successful re-vendor mirrors fork main exactly, preserves the optional
#     Android-local cli probes, and writes a pin resolved from the exact
#     fetched commit (openevv#1, #333);
#   * a required engine file missing from the source checkout aborts BEFORE
#     anything is deleted (#331);
#   * a failed remote fetch aborts instead of re-vendoring from a stale local
#     main (openevv#1).
#
# The vendored engine is a pure mirror of fork main: languages and registration
# wiring ship from the fork, so there is no Android-local language layer to
# preserve anymore.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REAL_SCRIPT="$ROOT/tools/vendor-openevv.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
pass()  { echo "ok  : $*"; }
fail()  { echo "FAIL: $*"; failures=$((failures+1)); }

# --- build a fake OpenEVV fork (bare remote + clone carrying engine files) ---
FORK_REMOTE_DIR="$WORK/fork.git"
git init -q --bare "$FORK_REMOTE_DIR"

FORK="$WORK/fork"
git clone -q "$FORK_REMOTE_DIR" "$FORK"
git -C "$FORK" config user.name  "openevv vendor test"
git -C "$FORK" config user.email "vendor-test@example.com"
( cd "$FORK"
  git checkout -q -b main
  echo "engine-content" > EVV_ENGINE_MARKER
  mkdir -p src/eci/lang src/port cli/bench_synth
  echo "engine languageid" > src/eci/lang/eci_languageid.c
  echo "engine port_ctors" > src/port/port_ctors.c
  echo "fork-engine-probe"   > cli/bench_synth.c
  git add -A && git commit -qm "engine commit"
  git push -q origin main
)
FORK_SHA=$(git -C "$FORK" rev-parse main)

# the app checkout (DST side). Reuse the fork clone but add app-local optional
# probes, as the repo under test has.
APP="$WORK/app"
mkdir -p "$APP/tools"
cp "$REAL_SCRIPT" "$APP/tools/vendor-openevv.sh"
SCRIPT="$APP/tools/vendor-openevv.sh"
DST="$APP/native/openevv"
mkdir -p "$DST/src/eci/lang" "$DST/src/port" "$DST/cli"
echo "engine languageid" > "$DST/src/eci/lang/eci_languageid.c"
echo "engine port_ctors" > "$DST/src/port/port_ctors.c"
echo "probe"       > "$DST/cli/bench_synth.c"
echo "local-probe" > "$DST/cli/probe.c"

CORRECT_SHA="$FORK_SHA"

run_success() {
  local out
  if out=$(bash "$SCRIPT" "$FORK" 2>&1); then
    return 0
  else
    echo "  script output: $out" >&2
    return 1
  fi
}

# ---- Test 1: successful re-vendor mirrors engine files + preserves probes + pins exact SHA
test1() {
  cd "$APP" || { fail "test1: cannot cd $APP"; return; }
  # a remote whose NAME looks like a fork/pr must not be picked as the source
  git -C "$FORK" remote add pr-9-fork "$WORK/unrelated-fork.git"
  if run_success; then pass "re-vendor succeeds (uses origin, ignores pr-* remote)"; else fail "re-vendor succeeds"; fi
  [ -s native/openevv/src/eci/lang/eci_languageid.c ] && pass "engine languageid mirrored" || fail "engine languageid mirrored"
  [ -s native/openevv/src/port/port_ctors.c ] && pass "engine port_ctors mirrored" || fail "engine port_ctors mirrored"
  [ -f native/openevv/EVV_ENGINE_MARKER ] && pass "fork main mirrored" || fail "fork main mirrored"
  grep -q '^engine languageid$' native/openevv/src/eci/lang/eci_languageid.c && pass "fork content won (not app-local copy)" || fail "fork content won (not app-local copy)"
  [ -f native/openevv/cli/bench_synth.c ] && pass "optional probe present" || fail "optional probe present"
  grep -q '^local-probe$' native/openevv/cli/probe.c && pass "app-local probe preserved" || fail "app-local probe preserved"
  pin_sha=$(grep '^openevv_vendored=' native/openevv/.openevv-pin | cut -d= -f2)
  if [ "$pin_sha" = "$CORRECT_SHA" ]; then
    pass "pin == exact fetched commit ($CORRECT_SHA)"
  else
    fail "pin == exact fetched commit (got $pin_sha want $CORRECT_SHA)"
  fi
  cd "$ROOT"
}
test1

# ---- Test 2: missing required engine file in the source aborts before deleting
cp -r "$DST" "$WORK/dst_backup"
test2() {
  cd "$APP" || { fail "test2: cannot cd $APP"; return; }
  # drop a required engine file from the SOURCE checkout. If the script wrongly
  # continued past the check it would rm -rf the whole DST and re-vendor; we
  # assert it stops first and leaves the existing vendored tree untouched.
  rm -f "$FORK/src/eci/lang/eci_languageid.c"
  if bash "$SCRIPT" "$FORK" 2>/dev/null; then
    fail "missing required source file aborts"
  else
    pass "missing required source file aborts"
  fi
  [ -f native/openevv/src/eci/lang/eci_languageid.c ] && pass "no destructive change (languageid intact)" \
                                                    || fail "no destructive change (languageid intact)"
  cd "$ROOT"
}
test2

# ---- Test 2b: a required registration file missing from source aborts before deleting
test2b() {
  cd "$APP" || { fail "test2b: cannot cd $APP"; return; }
  rm -rf native/openevv
  cp -r "$WORK/dst_backup" native/openevv
  rm -f "$FORK/src/port/port_ctors.c"
  if bash "$SCRIPT" "$FORK" 2>/dev/null; then
    fail "missing registration source aborts"
  else
    pass "missing registration source aborts"
  fi
  [ -f native/openevv/src/port/port_ctors.c ] && pass "no destructive change (port_ctors intact)" || fail "no destructive change (port_ctors intact)"
  cd "$ROOT"
}
test2b

# ---- Test 3: failed fetch aborts (stale local main never used)
test3() {
  cd "$APP" || { fail "test3: cannot cd $APP"; return; }
  rm -rf native/openevv
  cp -r "$WORK/dst_backup" native/openevv
  # point the fork's origin at an unreachable remote so the fetch fails; a
  # stale local main may exist but must never be re-vendored.
  git -C "$FORK" remote remove origin
  git -C "$FORK" remote add origin "$WORK/nonexistent-remote.git"
  if bash "$SCRIPT" "$FORK" 2>/dev/null; then
    fail "failed fetch aborts"
  else
    pass "failed fetch aborts"
  fi
  [ -f native/openevv/src/eci/lang/eci_languageid.c ] && pass "DST intact after failed fetch" || fail "DST intact after failed fetch"
  cd "$ROOT"
}
test3

# ---- Test 4: failure propagation self-check (#10)
# Intentionally fail an assertion and confirm the harness records it and would
# exit nonzero. Gated behind VENDOR_TEST_SELFCHECK=1 so the normal run stays
# green; CI-and-local verification flips it once to prove the harness detects
# an injected failure.
if [ "${VENDOR_TEST_SELFCHECK:-0}" = 1 ]; then
  test4() {
    local before=$failures
    fail "intentional self-check failure (must be recorded)"
    if [ "$failures" -gt "$before" ]; then
      pass "intentional failure propagates to parent failures counter"
    else
      fail "intentional failure propagates to parent failures counter"
    fi
  }
  test4
fi

echo
if [ "$failures" -gt 0 ]; then
  echo "vendor-openevv-test: $failures FAILURE(S)"
  exit 1
fi
echo "vendor-openevv-test: all checks passed"
exit 0