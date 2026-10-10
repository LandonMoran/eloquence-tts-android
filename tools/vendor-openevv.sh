#!/usr/bin/env bash
# tools/vendor-openevv.sh — re-vendor native/openevv from a given openevv checkout's `main` branch.
# Usage:  bash tools/vendor-openevv.sh <openevv-checkout-path> [upstream-sha]
# The openevv checkout must be a checkout of LandonMoran/openevv main (fork = upstream + our engine
# mods). Copies the shared engine tree, keeps the app-local additions
# ( cli probes, zh oracle bank(, and writes a pin file describing the sync source.
# NOTE: sources the branch that CONTAINS our src engine mods (followed_by_er, chs fusions,
# es_delete_checked); never `vendored`, which lacked them and dropped them in past syncs.
set -euo pipefail

SRC=${1:?openevv checkout path required}
UPSTREAM=${2:-unknown}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DST="$ROOT/native/openevv"
[ -d "$SRC/.git" ] || { echo "error: $SRC is not a git checkout" >&2; exit 1; }

cd "$SRC"
git fetch -q origin 2>/dev/null || true
FORK_REMOTE=$(git remote -v | awk '{print $1}' | grep -E 'pr|fork' | head -1 || true)
if [ -n "$FORK_REMOTE" ]; then git fetch -q "$FORK_REMOTE" main 2>/dev/null || true; fi
git checkout -q main 2>/dev/null || git checkout -q -b main --track origin/main 2>/dev/null || \
  git checkout -q -b main origin/main 2>/dev/null || exit 1
git reset --hard -q main

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
SHORT=$(git -C "$SRC" rev-parse --short main)
{
  echo "openevv_vendored=$(git -C "$SRC" rev-parse main)"
  echo "openevv_rebased_from=$SHORT"
  echo "openevv_source=fork main (LandonMoran/openevv; carries our engine mods)"
  echo "openevv_behind_upstream=0"
  if [ "$UPSTREAM" != unknown ]; then echo "openevv_upstream=$UPSTREAM"; fi
  date -u +'synced_at=%Y-%m-%dT%H:%M:%SZ'
} > "$DST/.openevv-pin"

echo "main@$SHORT"