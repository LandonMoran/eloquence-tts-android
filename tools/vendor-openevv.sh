#!/usr/bin/env bash
# tools/vendor-openevv.sh — re-vendor native/openevv to a given openevv checkout's `vendored` branch.
# Usage:  bash tools/vendor-openevv.sh <openevv-checkout-path> [upstream-sha]
# Copies the shared engine tree from the openevv checkout( says the app-local additions
# ( cli probes, zh oracle bank(, and writes a pin file describing the sync source.
set -euo pipefail

SRC=${1:?openevv checkout path required}
UPSTREAM=${2:-unknown}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DST="$ROOT/native/openevv"
[ -d "$SRC/.git" ] || { echo "error: $SRC is not a git checkout" >&2; exit 1; }

cd "$SRC"
git fetch -q origin 2>/dev/null || true
FORK_REMOTE=$(git remote -v | awk '{print $1}' | grep -E 'pr|fork' | head -1)
if [ -n "$FORK_REMOTE" ]; then git fetch -q "$FORK_REMOTE" vendored 2>/dev/null || true; fi
git checkout -q vendored 2>/dev/null || git checkout -q -b vendored --track origin/vendored 2>/dev/null || \
  git checkout -q -b vendored origin/main 2>/dev/null || exit 1
git reset --hard -q vendored

# preserve app-local additions
SNAP=$(mktemp -d)
mkdir -p "$SNAP/cli" "$SNAP/lang"
cp -a "$DST/cli"/bench_synth.c "$DST/cli"/langdump.c "$DST/cli"/oracleprobe.c \
      "$DST/cli"/phprobe.c "$DST/cli"/romprobe.c "$DST/cli"/uncosp_test.c \
      "$DST/cli"/probe.c "$SNAP/cli"/ 2>/dev/null || true
cp -a "$DST/lang/chs" "$SNAP/lang/" 2>/dev/null || true

rm -rf "$DST"; mkdir -p "$DST"
( cd "$SRC" && tar -c --exclude='./.git' --exclude='./build' --exclude='./test' --exclude='./win' \
       --exclude='./reference' --exclude='./.github' --exclude='./flake.nix' --exclude='./flake.lock' \
       --exclude='./CLAUDE.md' --exclude='./.gitignore' --exclude='*.o' --exclude='*.a' . ) \
 | ( cd "$DST" && tar -x )

cp -a "$SNAP"/. "$DST"/
rm -rf "$SNAP"

# pin file describing the sync source
SHORT=$(git -C "$SRC" rev-parse --short vendored)
{
  echo "openevv_vendored=$(git -C "$SRC" rev-parse vendored)"
  echo "openevv_rebased_from=$SHORT"
  echo "openevv_behind_upstream=0"
  if [ "$UPSTREAM" != unknown ]; then echo "openevv_upstream=$UPSTREAM"; fi
  date -u +'synced_at=%Y-%m-%dT%H:%M:%SZ'
} > "$DST/.openevv-pin"

echo "vendored@$SHORT"