#!/usr/bin/env bash
# PreToolUse(Edit|Write|MultiEdit|NotebookEdit): protect enforcement config from edits.
set -u
input=$(cat)
[ "${FOLIO_UNLOCK_CONFIG:-0}" = "1" ] && exit 0
path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' 2>/dev/null)
[ -z "$path" ] && exit 0
p=$(printf '%s' "$path" | tr '\\' '/')
case "$p" in
  */.claude/hooks/*|.claude/hooks/*|*/.claude/settings.json|.claude/settings.json|*/scripts/device/*|scripts/device/*|*/.gitattributes|.gitattributes)
    printf 'BLOCKED: %s is protected config. Record the exact change as a Blocked item in docs/plan/STATUS.md; the user applies it or restarts Claude with FOLIO_UNLOCK_CONFIG=1.\n' "$path" >&2
    exit 2 ;;
esac
exit 0
