#!/usr/bin/env bash
# tools/vendor-openevv.sh — re-vendor native/openevv from a given openevv checkout's `main` branch.
# Usage:  bash tools/vendor-openevv.sh <openevv-checkout-path> [upstream-sha]
#
# Sources of truth (intentional split — do not "fix" this):
#   * The OpenEVV engine is vendored directly from upstream (Mudb0y/openevv)
#     main. This script copies that tree as-is; there is no maintained fork
#     anymore (the local fork LandonMoran/openevv was retired and the library
#     is consumed un-edited from upstream).
#   * The vendored engine is a pure mirror of upstream main: languages, ROMs,
#     and registration wiring all ship from upstream. There is no Android-local
#     language layer, so nothing must be re-spliced across a re-vendor.
#   * cli/* probe tools and the vendor script itself are Android-local too,
#     but they are optional instrumentation, not runtime requirements.
# The vendor sync script therefore copies upstream main exactly and FAILS FAST
# on a failed fetch or a missing source checkout, never leaving $DST half-updated.
set -euo pipefail

SRC=${1:?openevv checkout path required}
UPSTREAM=${2:-unknown}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DST="$ROOT/native/openevv"
[ -d "$SRC/.git" ] || { echo "error: $SRC is not a git checkout" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Fetch the intended remote branch and FAIL if the fetch fails, so we never
#    continue with a stale local main and never write a pin for it. We also do
#    not touch $DST before this succeeds, so a failed/missing source leaves the
#    existing vendored tree untouched.
# ---------------------------------------------------------------------------
cd "$SRC"

# The intended source is the checkout's main, which is upstream's tree.
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
# actually checked out, not some possibly-stale local 'main' ref.
FETCH_SHA=$(git rev-parse "FETCH_HEAD^{commit}") || { echo "error: revision of $REMOTE/main is not a commit" >&2; exit 1; }
git checkout -q --detach "$FETCH_SHA" 2>/dev/null \
  || { echo "error: could not check out $REMOTE/main ($FETCH_SHA)" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 2. Pre-validate that the source checkout carries the shipped engine files
#    BEFORE we delete $DST, so an incomplete source aborts the sync before any
#    destructive step and leaves the existing vendored tree untouched. (#331)
# ---------------------------------------------------------------------------
REQUIRED_SRC=(
  "src/eci/lang/eci_languageid.c"
  "src/port/port_ctors.c"
)
for f in "${REQUIRED_SRC[@]}"; do
  if [ ! -s "$SRC/$f" ]; then
    echo "error: required engine file '$f' missing from $SRC; aborting before any change (nothing was deleted)" >&2
    exit 1
  fi
done

# ---------------------------------------------------------------------------
# 3. The vendored engine is a pure mirror of upstream main. Preserve the optional
#    Android-local cli probes across the re-vendor; nothing else is local.
# ---------------------------------------------------------------------------
SNAP=$(mktemp -d)
for p in cli/bench_synth.c cli/langdump.c cli/uncosp_test.c cli/probe.c; do
  if [ -f "$DST/$p" ]; then
    mkdir -p "$SNAP/cli"
    cp -a "$DST/$p" "$SNAP/cli/" || { echo "error: failed to copy optional probe '$p'" >&2; exit 1; }
  fi
done

rm -rf "$DST"; mkdir -p "$DST"
( cd "$SRC" && tar -c --exclude='./.git' --exclude='./build' --exclude='./test' --exclude='./win' \
       --exclude='./reference' --exclude='./.github' --exclude='./flake.nix' --exclude='./flake.lock' \
       --exclude='./CLAUDE.md' --exclude='./.gitignore' --exclude='*.o' --exclude='*.a' . ) \
  | ( cd "$DST" && tar -x )

cp -a "$SNAP"/. "$DST"/ 2>/dev/null || true
rm -rf "$SNAP"

# ---------------------------------------------------------------------------
# 4. Post-restore verification: the mirror must carry the shipped engine files;
#    abort if it does not.
# ---------------------------------------------------------------------------
for f in "${REQUIRED_SRC[@]}"; do
  if [ ! -s "$DST/$f" ]; then
    echo "error: required engine file '$f' missing after restore; re-vendor failed" >&2
    exit 1
  fi
done

# ---------------------------------------------------------------------------
# 5. Pin file describing the sync source (from the exact fetched commit).
# ---------------------------------------------------------------------------
SHORT=$(git -C "$SRC" rev-parse --short "$FETCH_SHA")
{
  echo "openevv_vendored=$FETCH_SHA"
  echo "openevv_rebased_from=$SHORT"
  echo "openevv_source=$REMOTE main (Mudb0y/openevv upstream; vendored un-edited)"
  echo "openevv_behind_upstream=0"
  if [ "$UPSTREAM" != unknown ]; then echo "openevv_upstream=$UPSTREAM"; fi
} > "$DST/.openevv-pin"

echo "main@$SHORT"
echo "vendored $REMOTE/main ($FETCH_SHA)"