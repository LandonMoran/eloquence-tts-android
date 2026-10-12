#!/usr/bin/env bash
# tools/vendor-openevv.sh — re-vendor native/openevv from a given openevv checkout's `main` branch.
# Usage:  bash tools/vendor-openevv.sh <openevv-checkout-path> [upstream-sha]
#
# Sources of truth (intentional split — do not "fix" this):
#   * Shared OpenEVV engine changes, incl. our maintained engine mods
#     (followed_by_er, chs fusions, es_delete_checked), come from the OpenEVV
#     fork (LandonMoran/openevv) main. This script copies that engineered tree.
#   * The Chinese implementation is Android-local because upstream OpenEVV has
#     no Chinese support: lang/chs (Mandarin language module + oracle bank),
#     rom/chs (chs Mandarin ROM: chsrom.c + rom_tables_chs.*), and the engine
#     registration wiring (src/port/port_ctors.c: chs_register) are NOT in the
#     fork and MUST be preserved across every re-vendor. Without them,
#     eciNewEx(0x60000) returns NULL and chs-smoke fails.
#   * cli/* probe tools and the vendor script itself are Android-local too,
#     but they are optional instrumentation, not runtime requirements: their
#     absence is reported, only required files abort the sync.
# The vendor sync script therefore preserves the Android-local Chinese files
# across re-vendoring and FAILS FAST if a required one cannot be preserved.
set -euo pipefail

SRC=${1:?openevv checkout path required}
UPSTREAM=${2:-unknown}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DST="$ROOT/native/openevv"
[ -d "$SRC/.git" ] || { echo "error: $SRC is not a git checkout" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Pre-validate that every REQUIRED app-local source exists BEFORE we delete
#    $DST, so a missing file aborts the sync before any destructive step and
#    leaves the existing vendored tree untouched. (#331)
# ---------------------------------------------------------------------------
# Required runtime files: the Chinese implementation + its committed lookup
# tables + its registration wiring. All of these must be present (and the
# wiring must actually reference chs_register) BEFORE the destructive rm.
REQUIRED_SRC=(
  "lang/chs"
  "rom/chs/chsrom.c"
  "rom/chs/rom_tables_chs.c"
  "rom/chs/rom_tables_chs.h"
  "src/port/port_ctors.c"
)
# Optional instrumentation (cli probes). Absence is OK but must be reported.
OPTIONAL_SRC=(
  "cli/bench_synth.c"
  "cli/langdump.c"
  "cli/oracleprobe.c"
  "cli/phprobe.c"
  "cli/romprobe.c"
  "cli/uncosp_test.c"
  "cli/probe.c"
)

for f in "${REQUIRED_SRC[@]}"; do
  if [ ! -e "$DST/$f" ]; then
    echo "error: required Android-local file '$f' missing from $DST; aborting before any change (nothing was deleted)" >&2
    exit 1
  fi
done

# The registration wiring must actually reference chs_register, not merely
# exist as a file: a stub or once-removed port_ctors.c would silently drop
# the Chinese ROM from eciNewEx(0x60000). Verified here, before the rm.
if [ ! -s "$DST/src/port/port_ctors.c" ] \
   || ! grep -q 'chs_register' "$DST/src/port/port_ctors.c"; then
  echo "error: chs_register registration missing from native/openevv before re-vendor; aborting before any change (nothing was deleted)" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 2. Fetch the intended remote branch and FAIL if the fetch fails, so we never
#    continue with a stale local main and never write a pin for it. (#openevv1)
# ---------------------------------------------------------------------------
cd "$SRC"

# The intended source is the checkout's main, which carries our engine mods.
# We never guess the source from a remote NAME: a remote named "pr-*" or a
# random "fork-*" is not proof it is the intended fork (unrelated remotes
# could be mistaken for the source). Default to the checkout's primary
# remote `origin`; an explicit override may be supplied via OPENE_VV_REMOTE.
# Fetching 'main' must succeed or we stop immediately.
REMOTE="${OPENE_VV_REMOTE:-origin}"
if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
    echo "error: source remote '$REMOTE' is not configured in $SRC; refusing to guess" >&2
    exit 1
fi
if ! git fetch -q "$REMOTE" main; then
  echo "error: 'git fetch $REMOTE main' in $SRC failed; not re-vendoring from a stale or unreachable branch" >&2
  exit 1
fi

# Resolve the pin from the exact fetched commit (FETCH_HEAD), which is what we
# actually checked out, not some possibly-stale local 'main' ref. (#openevv1)
FETCH_SHA=$(git rev-parse "FETCH_HEAD^{commit}") || { echo "error: revision of $REMOTE/main is not a commit" >&2; exit 1; }
git checkout -q --detach "$FETCH_SHA" 2>/dev/null \
  || { echo "error: could not check out $REMOTE/main ($FETCH_SHA)" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 3. Preserve the Android-local additions in the vendored tree.
#    Required files are copied fail-fast (set -e makes a failed cp abort the
#    whole sync BEFORE the blow-away below). Optional probes are attempted
#    and their absence reported, never silently suppressed. (#331, #333)
# ---------------------------------------------------------------------------
SNAP=$(mktemp -d)
mkdir -p "$SNAP/cli" "$SNAP/lang" "$SNAP/rom/chs" "$SNAP/src/port"
cp -a "$DST/lang/chs"        "$SNAP/lang/"
cp -a "$DST/rom/chs"/.       "$SNAP/rom/chs/"
cp -a "$DST/src/port/port_ctors.c" "$SNAP/src/port/"
for p in "${OPTIONAL_SRC[@]}"; do
  if [ -f "$DST/$p" ]; then
    cp -a "$DST/$p" "$SNAP/cli/" || { echo "error: failed to copy optional probe '$p'" >&2; exit 1; }
  else
    echo "warning: optional probe '$p' not present; skipping (not a required file)" >&2
  fi
done

rm -rf "$DST"; mkdir -p "$DST"
( cd "$SRC" && tar -c --exclude='./.git' --exclude='./build' --exclude='./test' --exclude='./win' \
       --exclude='./reference' --exclude='./.github' --exclude='./flake.nix' --exclude='./flake.lock' \
       --exclude='./CLAUDE.md' --exclude='./.gitignore' --exclude='*.o' --exclude='*.a' . ) \
| ( cd "$DST" && tar -x )

cp -a "$SNAP"/. "$DST"/
rm -rf "$SNAP"

# ---------------------------------------------------------------------------
# 4. Post-restore verification: the required Chinese runtime + registration
#    must be present after the copy; abort if they are not. (#331)
# ---------------------------------------------------------------------------
for f in "${REQUIRED_SRC[@]}"; do
  if [ ! -e "$DST/$f" ]; then
    echo "error: required Android-local file '$f' missing after restore; re-vendor failed" >&2
    exit 1
  fi
done
if [ ! -s "$DST/src/port/port_ctors.c" ] \
   || ! grep -q 'chs_register' "$DST/src/port/port_ctors.c"; then
  echo "error: chs_register registration missing from native/openevv after restore" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 5. Pin file describing the sync source (from the exact fetched commit).
# ---------------------------------------------------------------------------
SHORT=$(git -C "$SRC" rev-parse --short "$FETCH_SHA")
{
  echo "openevv_vendored=$FETCH_SHA"
  echo "openevv_rebased_from=$SHORT"
  echo "openevv_source=$REMOTE main (LandonMoran/openevv; carries our engine mods)"
  echo "openevv_behind_upstream=0"
  if [ "$UPSTREAM" != unknown ]; then echo "openevv_upstream=$UPSTREAM"; fi
} > "$DST/.openevv-pin"

echo "main@$SHORT"
echo "vendored $REMOTE/main ($FETCH_SHA); Android-local Chinese files preserved"