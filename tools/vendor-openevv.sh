#!/usr/bin/env bash
# tools/vendor-openevv.sh — bump the native/openevv engine submodule to the
# current upstream Mudb0y/openevv `main`, PREVIEW the incoming delta (read-only),
# and stage the gitlink pointer bump. This supersedes the old flat-copy vendor
# flow: the engine is now a git submodule, so "re-vendoring" = moving the
# pinned SHA. It is the release-time engine freshness step.
#
# Usage:
#   bash tools/vendor-openevv.sh                # preview what main brings, no stage
#   bash tools/vendor-openevv.sh --apply        # move the submodule to main + git add
#
# Never auto-merges. Always leaves the pointer change staged for a real PR, so
# the engine delta is reviewed as its own change before it ships. (Submodules
# hide content behind a gitlink, so the PR body must describe the delta — see
# the printed summary.)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="native/openevv"
SUBM="$ROOT/$MOD"
APPLY=0
if [ "${1:-}" = "--apply" ]; then APPLY=1; fi

# The submodule must already be initialized; we never clone on demand here.
[ -d "$SUBM/.git" ] || [ -e "$SUBM/.git" ] || {
  echo "error: $MOD is not an initialized submodule. Run:" >&2
  echo "  git submodule update --init --recursive" >&2
  exit 1
}

CUR=$(git -C "$SUBM" rev-parse HEAD)

# ---------------------------------------------------------------------------
# READ-ONLY PREVIEW: show exactly what an update to main would bring, before
# any pointer is moved. This is the review path — intentional split.
# ---------------------------------------------------------------------------
echo "== engine submodule =="
git -C "$SUBM" remote get-url origin
echo "current pin: $CUR ($(git -C "$SUBM" log -1 --format='%s' "$CUR"))"
git -C "$SUBM" fetch -q origin main

# Set the target to what we actually fetched, never a possibly-stale local ref.
TARGET=$(git -C "$SUBM" rev-parse "FETCH_HEAD^{commit}")
echo "upstream main: $TARGET ($(git -C "$SUBM" log -1 --format='%s' "$TARGET"))"

if [ "$CUR" = "$TARGET" ]; then
  echo "ALREADY UP TO DATE: pin == upstream main. Nothing to do."
  exit 0
fi

if ! git -C "$SUBM" merge-base --is-ancestor "$CUR" "$TARGET" 2>/dev/null; then
  echo "WARNING: current pin is not an ancestor of main (history rewritten / force-push)." >&2
  echo "         Review '$CUR' carefully before bumping." >&2
fi

echo ""
echo "== incoming commits: $CUR..$TARGET =="
git -C "$SUBM" log --oneline "$CUR..$TARGET"
echo ""
echo "== files changed across the bump =="
git -C "$SUBM" diff --stat "$CUR..$TARGET"

# ---------------------------------------------------------------------------
# APPLY (only with --apply): move the submodule pointer to main and stage the
# gitlink. This is the whole "re-vendor". Safe: creates no copy, deletes nothing
# (the old engine object stays in submodule history forever).
# ---------------------------------------------------------------------------
if [ "$APPLY" = 1 ]; then
  # Detached at FETCH_HEAD so the pinned tree == exactly what we previewed.
  git -C "$SUBM" checkout -q --detach "$TARGET"
  git -C "$ROOT" add "$MOD"
  echo ""
  echo "== applied: submodule bumped + staged. Suggested PR body: =="
  echo "  chore(engine): bump openevv $(git -C "$SUBM" rev-parse --short "$CUR")->$(git -C "$SUBM" rev-parse --short "$TARGET")"
  echo ""
  git -C "$SUBM" log --oneline "$CUR..$TARGET" | sed 's/^/  /'
else
  echo ""
  echo "Preview only (read-only). Re-run with --apply to move the pin."
fi