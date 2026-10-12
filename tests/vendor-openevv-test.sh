#!/usr/bin/env bash
# tests/vendor-openevv-test.sh — regression tests for tools/vendor-openevv.sh.
#
# Exercises, with a throwaway git repo standing in for the OpenEVV fork:
#   * a successful re-vendor preserves the Android-local Chinese runtime
#     (lang/chs, rom/chs, src/port/port_ctors.c with chs_register) and writes
#     a pin resolved from the exact fetched commit (openevv#1, #333);
#   * a required app-local file missing aborts BEFORE anything is deleted
#     (#331);
#   * a failed remote fetch aborts instead of re-vendoring from a stale local
#     main (openevv#1).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REAL_SCRIPT="$ROOT/tools/vendor-openevv.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
pass()  { echo "ok  : $*"; }
fail()  { echo "FAIL: $*"; failures=$((failures+1)); }

# --- build a fake OpenEVV fork (bare remote + clone with an app-local DST) ---
# The vendor script derives the app root from its OWN location
# (dirname $0/..), so we run an app-local copy of the script rather than the
# real one — otherwise ROOT would resolve to this repository and the test
# would re-vendor the real native/openevv tree. Never run the copy against
# anything you care about.
FORK_REMOTE_DIR="$WORK/fork.git"
git init -q --bare "$FORK_REMOTE_DIR"

FORK="$WORK/fork"
git clone -q "$FORK_REMOTE_DIR" "$FORK"
# The CI runner has no git identity by default; without it the fixture's
# own `git commit` below fails and `main` never exists, which would make the
# harness report a (bogus) failure for an environment reason, not a code bug.
git -C "$FORK" config user.name  "openevv vendor test"
git -C "$FORK" config user.email "vendor-test@example.com"
( cd "$FORK"
  git checkout -q -b main
  echo "engine-content" > EVV_ENGINE_MARKER
  git add -A && git commit -qm "engine commit"
  git push -q origin main
)
FORK_SHA=$(git -C "$FORK" rev-parse main)

# the app checkout (DST side). Reuse the fork clone but add app-local files to
# native/openevv, as the repo under test has.
APP="$WORK/app"
mkdir -p "$APP/tools"
cp "$REAL_SCRIPT" "$APP/tools/vendor-openevv.sh"
SCRIPT="$APP/tools/vendor-openevv.sh"
DST="$APP/native/openevv"
mkdir -p "$DST/lang/chs" "$DST/rom/chs" "$DST/src/port" "$DST/cli"
echo "chs-rules"   > "$DST/lang/chs/rule"
echo "chs-rom"     > "$DST/rom/chs/chsrom.c"
echo "table"       > "$DST/rom/chs/rom_tables_chs.c"
echo "tableh"      > "$DST/rom/chs/rom_tables_chs.h"
cat > "$DST/src/port/port_ctors.c" <<EOF
#include <stdio.h>
void chs_register(void) { fprintf(stderr, "registered chs\n"); }
EOF
echo "probe"       > "$DST/cli/bench_synth.c"
echo "oracle"      > "$DST/cli/oracleprobe.c"

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

# ---- Test 1: successful re-vendor preserves required files + pins exact SHA
# NOTE: run in the PARENT shell (not a subshell) so that `fail` increments
# the shared `failures` counter that the final check reads. A subshell would
# hide these increments and let failures slip through. (#10)
test1() {
  cd "$APP"
  # Finding #8: a remote whose NAME looks like a fork/pr must not be picked
  # as the source. Add an unrelated "pr-9-fork" remote with a bogus URL; if
  # the script guessed from the name it would try to fetch from it and fail
  # (or worse, sync from the wrong repo). It must use origin instead.
  git -C "$FORK" remote add pr-9-fork "$WORK/unrelated-fork.git"
  if run_success; then pass "re-vendor succeeds (uses origin, ignores pr-* remote)"; else fail "re-vendor succeeds"; fi
  [ -f native/openevv/lang/chs/rule ]  && pass "lang/chs preserved"   || fail "lang/chs preserved"
  [ -f native/openevv/rom/chs/chsrom.c ]&& pass "rom/chs preserved"    || fail "rom/chs preserved"
  [ -f native/openevv/src/port/port_ctors.c ] && pass "port_ctors.c preserved" || fail "port_ctors.c preserved"
  grep -q 'chs_register' native/openevv/src/port/port_ctors.c \
      && pass "chs_register registration preserved" || fail "chs_register preserved"
  [ -f native/openevv/cli/bench_synth.c ] && pass "optional probe present" || fail "optional probe present"
  pin_sha=$(grep '^openevv_vendored=' native/openevv/.openevv-pin | cut -d= -f2)
  if [ "$pin_sha" = "$CORRECT_SHA" ]; then
    pass "pin == exact fetched commit ($CORRECT_SHA)"
  else
    fail "pin == exact fetched commit (got $pin_sha want $CORRECT_SHA)"
  fi
  cd "$ROOT"
}
test1

# ---- Test 2: missing required file aborts before deleting anything
cp -r "$DST" "$WORK/dst_backup"
test2() {
  cd "$APP"
  # drop a required file. If the script wrongly continued past the check it
  # would rm -rf the whole DST and re-vendor; we assert it stops first.
  rm -f native/openevv/rom/chs/chsrom.c
  if bash "$SCRIPT" "$FORK" 2>/dev/null; then
    fail "missing required file aborts"
  else
    pass "missing required file aborts"
  fi
  # no destructive change: the other required files must still be there
  [ -f native/openevv/lang/chs/rule ] && pass "no destructive change (lang/chs intact)" \
                                  || fail "no destructive change (lang/chs intact)"
  [ -d native/openevv/rom/chs ] && pass "rom/chs dir intact" || fail "rom/chs dir intact"
  cd "$ROOT"
}
test2

# ---- Test 2b: a required Chinese ROM table missing aborts before deleting
test2b() {
  cd "$APP"
  # restore DST from backup (Test 2 corrupted only chsrom.c; rm it again)
  rm -rf native/openevv
  cp -r "$WORK/dst_backup" native/openevv
  # remove a required table; the preflight must abort before any change
  rm -f native/openevv/rom/chs/rom_tables_chs.c
  if bash "$SCRIPT" "$FORK" 2>/dev/null; then
    fail "missing ROM table aborts"
  else
    pass "missing ROM table aborts"
  fi
  [ -f native/openevv/rom/chs/chsrom.c ] && pass "no destructive change (chsrom.c intact)" \
                                       || fail "no destructive change (chsrom.c intact)"
  cd "$ROOT"
}
test2b

# ---- Test 2c: missing chs_register wiring aborts before deleting
test2c() {
  cd "$APP"
  rm -rf native/openevv
  cp -r "$WORK/dst_backup" native/openevv
  # strip the chs_register symbol from port_ctors.c; the preflight wiring
  # check must abort before any change (finding 9)
  grep -v 'chs_register' native/openevv/src/port/port_ctors.c > native/openevv/src/port/port_ctors.c.tmp
  mv native/openevv/src/port/port_ctors.c.tmp native/openevv/src/port/port_ctors.c
  if bash "$SCRIPT" "$FORK" 2>/dev/null; then
    fail "missing chs_register wiring aborts"
  else
    pass "missing chs_register wiring aborts"
  fi
  [ -f native/openevv/rom/chs/chsrom.c ] && pass "no destructive change (rom intact)" \
                                       || fail "no destructive change (rom intact)"
  cd "$ROOT"
}
test2c

# ---- Test 3: failed fetch aborts (stale local main never used)
test3() {
  cd "$APP"
  # restore the DST that Test 2 corrupted
  rm -rf native/openevv
  cp -r "$WORK/dst_backup" native/openevv
  [ -f native/openevv/rom/chs/chsrom.c ] || echo "SETUP ERROR: dst_backup missing"
  # point the fork's origin at an unreachable remote so `git fetch origin main`
  # fails; a stale local main may exist but must never be re-vendored.
  git -C "$FORK" remote remove origin
  git -C "$FORK" remote add origin "$WORK/nonexistent-remote.git"
  if bash "$SCRIPT" "$FORK" 2>/dev/null; then
    fail "failed fetch aborts"
  else
    pass "failed fetch aborts"
  fi
  # app DST untouched by the failed attempt
  [ -f native/openevv/rom/chs/chsrom.c ] && pass "DST intact after failed fetch" || fail "DST intact after failed fetch"
  cd "$ROOT"
}
test3

# ---- Test 4: failure propagation self-check (finding #10)
# Intentionally fail an assertion and confirm the harness records it and would
# exit nonzero, proving subshell-hidden failures are gone. Gated behind
# VENDOR_TEST_SELFCHECK=1 so the normal run stays green; CI-and-local
# verification flips it once to prove the harness detects an injected failure.
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