#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): enforce size limits of memory files.
# Exit 2 after the write shows the message to Claude so it compacts immediately.
set -u
input=$(cat)
path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null | tr '\\' '/')
[ -z "$path" ] && exit 0
if [ ! -f "$path" ] && [ -n "${CLAUDE_PROJECT_DIR:-}" ]; then
  path="$(printf '%s' "$CLAUDE_PROJECT_DIR" | tr '\\' '/')/$path"
fi
[ -f "$path" ] || exit 0

count() { wc -l < "$1" | tr -d ' '; }
fail() {
  printf 'MEMORY LIMIT: %s Run the maintain-memory skill now, then re-check with wc -l.\n' "$1" >&2
  exit 2
}

case "$path" in
  */CLAUDE.md|CLAUDE.md)
    n=$(count "$path")
    [ "$n" -le 200 ] || fail "CLAUDE.md has $n lines (limit 200)."
    learned=$(awk '/^## Learned/{f=1;next} /^## /{f=0} f && /^- /{c++} END{print c+0}' "$path")
    [ "$learned" -le 20 ] || fail "CLAUDE.md Learned section has $learned entries (limit 20)."
    ;;
  */.claude/rules/*.md|.claude/rules/*.md)
    n=$(count "$path")
    [ "$n" -le 120 ] || fail "$(basename "$path") has $n lines (limit 120 per rule file)."
    ;;
  */docs/plan/STATUS.md|docs/plan/STATUS.md)
    n=$(count "$path")
    [ "$n" -le 60 ] || fail "STATUS.md has $n lines (limit 60). Collapse finished phases to one line."
    ;;
  */.claude/skills/*/SKILL.md|.claude/skills/*/SKILL.md)
    n=$(count "$path")
    [ "$n" -le 200 ] || fail "$(dirname "$path")/SKILL.md has $n lines (limit 200)."
    ;;
esac
exit 0
