#!/usr/bin/env bash
# Unattended build loop (started by the USER, never by Claude).
# Runs one fresh headless Claude Code session per task until the plan is done, something is Blocked,
# the max count is reached, or two runs in a row make no progress.
# Usage: bash scripts/auto/run-loop.sh [maxTasks=10]
# Prerequisite: run `claude` interactively once in this repo and accept the workspace trust prompt.
set -u
cd "$(dirname "$0")/../.." || exit 1
max="${1:-10}"
mkdir -p .device/loop-logs
status_file="docs/plan/STATUS.md"
no_progress=0

blocked_count() {
  awk '/^## Blocked/{f=1;next} /^## /{f=0} f && /^- / && !/\(none\)/{c++} END{print c+0}' "$status_file"
}

for i in $(seq 1 "$max"); do
  if grep -qE '^next: DONE' "$status_file"; then echo "Plan complete."; exit 0; fi
  if [ "$(blocked_count)" -gt 0 ]; then echo "Blocked items present in STATUS.md. Resolve them, then rerun."; exit 3; fi

  head_before="$(git rev-parse HEAD 2>/dev/null || echo none)"
  status_before="$(sha256sum "$status_file" | cut -d' ' -f1)"
  next="$(sed -nE 's/^next: (.*)$/\1/p' "$status_file" | head -n1)"
  log=".device/loop-logs/$(date +%Y%m%d-%H%M%S)-${next// /_}.log"
  echo "[$i/$max] $next -> $log"

  claude -p "Use the next-task skill: execute exactly one task (or one phase REVIEW) from docs/plan/STATUS.md, verify it, commit it, update STATUS.md, then stop with a 3-line summary." \
    --permission-mode dontAsk --max-turns 250 > "$log" 2>&1 || echo "  claude exited non-zero (see log)"

  tail -n 3 "$log" | sed 's/^/  /'
  head_after="$(git rev-parse HEAD 2>/dev/null || echo none)"
  status_after="$(sha256sum "$status_file" | cut -d' ' -f1)"
  if [ "$head_before" = "$head_after" ] && [ "$status_before" = "$status_after" ]; then
    no_progress=$((no_progress + 1))
    if [ "$no_progress" -ge 2 ]; then echo "No progress in two runs. Stopping. Check the logs."; exit 4; fi
  else
    no_progress=0
  fi
done
echo "Reached max task count ($max)."
