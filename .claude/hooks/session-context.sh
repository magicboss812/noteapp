#!/usr/bin/env bash
# SessionStart hook. Plain stdout becomes session context, so keep it short and factual.
set -u
cat > /dev/null 2>&1 || true
root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0

echo "Project status snapshot from docs/plan/STATUS.md:"
if [ -f docs/plan/STATUS.md ]; then
  head -n 60 docs/plan/STATUS.md
else
  echo "STATUS.md is missing."
fi

branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
last=$(git log -1 --pretty='%h %s' 2>/dev/null || echo "none")
dirty=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
echo ""
echo "Git: branch ${branch}; last commit: ${last}; uncommitted files: ${dirty}."
if [ -f .device/serial ]; then
  echo "Last device target: $(tr -d '\r\n' < .device/serial)."
fi
exit 0
