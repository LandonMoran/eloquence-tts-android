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
mkdir -p "$DST/lang/chs" "$DST/rom/chs/tbl" "$DST/src/port" "$DST/cli"
echo "chs-rules"   > "$DST/lang/chs/rule"
echo "chs-rom"     > "$DST/rom/chs/chsrom.c"
echo "table"       > "$DST/rom/chs/tbl/rom_tables_chs.c"
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
( cd "$APP"
  if run_success; then pass "re-vendor succeeds"; else fail "re-vendor succeeds"; fi
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
)

# ---- Test 2: missing required file aborts before deleting anything
cp -r "$DST" "$WORK/dst_backup"
( cd "$APP"
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
)

# ---- Test 3: failed fetch aborts (stale local main never used)
( cd "$APP"
  # restore the DST that Test 2 deliberately corrupted
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
)

echo
if [ "$failures" -gt 0 ]; then
  echo "vendor-openevv-test: $failures FAILURE(S)"
  exit 1
fi
echo "vendor-openevv-test: all checks passed"
exit 0