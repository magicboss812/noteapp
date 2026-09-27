#!/usr/bin/env bash
# PreToolUse(Bash|PowerShell) guard. Exit 2 blocks the call and shows stderr to Claude.
# Blocks: raw adb/fastboot, nested claude, the user-only automation loop, destructive git,
# recursive rm outside build/cache dirs, network hosts outside the allowlist,
# shell writes to protected config paths (unless FOLIO_UNLOCK_CONFIG=1).
set -u
input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null)
[ -z "$cmd" ] && exit 0

block() {
  printf 'BLOCKED by guard-bash: %s\n' "$1" >&2
  exit 2
}

# 1) Inspect the first word of every command segment.
segments=$(printf '%s' "$cmd" | sed -E 's/(\|\||&&|;|\||\$\(|`|\(|\))/\n/g')
while IFS= read -r seg; do
  seg=$(printf '%s' "$seg" | sed -E 's/^[[:space:]]+//')
  seg=$(printf '%s' "$seg" | sed -E 's/^([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]+)*//')
  seg=$(printf '%s' "$seg" | sed -E 's/^(env|xargs|exec|nohup|time|sudo|command|nice|stdbuf)[[:space:]]+//')
  first=${seg%%[[:space:]]*}
  first=$(printf '%s' "$first" | tr -d "\"'")
  base=${first##*/}
  base=${base##*\\}
  case "$base" in
    adb|adb.exe|fastboot|fastboot.exe)
      block "raw adb/fastboot is not allowed. Use bash scripts/device/*.sh (see the device-test skill)." ;;
    claude|claude.exe)
      block "starting Claude Code from inside a session is not allowed." ;;
    sudo)
      block "sudo is not allowed. Record a Blocked item for the user instead." ;;
  esac
done <<SEGMENTS
$segments
SEGMENTS

# 2) The unattended loop is started by the user only.
if printf '%s' "$cmd" | grep -Eq 'scripts/auto/'; then
  block "scripts/auto/* is started by the user, not from inside a session."
fi

# 3) Destructive git.
if printf '%s' "$cmd" | grep -Eq 'git[[:space:]]+push([[:space:]][^;&|]*)?[[:space:]](-f|--force|--force-with-lease)([[:space:]]|$)'; then
  block "force push is not allowed."
fi
if printf '%s' "$cmd" | grep -Eq 'git[[:space:]]+(reset[[:space:]]+--hard|clean[[:space:]]+-[A-Za-z]*[fdx])'; then
  block "git reset --hard / git clean are not allowed. Use git stash or git restore <paths>."
fi

# 4) Recursive rm only inside build output, caches, .device/ or temp.
if printf '%s' "$cmd" | grep -Eq '(^|[;&|[:space:]])rm[[:space:]]+(-[A-Za-z]*[rR]|--recursive)'; then
  targets=$(printf '%s' "$cmd" | grep -Eo 'rm[[:space:]]+[^;&|]*' | sed -E 's/^rm[[:space:]]+//' | tr ' ' '\n' | grep -Ev '^-' | grep -Ev '^$' || true)
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    p=$(printf '%s' "$p" | tr -d "\"'")
    p=${p#./}
    case "$p" in
      build|build/*|*/build|*/build/*|.device|.device/*|.gradle/*|*/.gradle/*|/tmp/*|spikes/scratch/*) ;;
      *) block "recursive rm of '$p' is not allowed (only build/, .gradle/, .device/, /tmp/). Use git rm for tracked files." ;;
    esac
  done <<TARGETS
$targets
TARGETS
fi

# 5) Network commands only to allowlisted hosts.
if printf '%s' "$cmd" | grep -Eq '(^|[;&|[:space:]])(curl|wget|Invoke-WebRequest|iwr)([[:space:]]|$)'; then
  urls=$(printf '%s' "$cmd" | grep -Eo 'https?://[^[:space:]"'"'"']+' || true)
  [ -z "$urls" ] && block "network command without an explicit https URL."
  while IFS= read -r u; do
    [ -z "$u" ] && continue
    host=$(printf '%s' "$u" | sed -E 's#^https?://([^/:]+).*#\1#')
    case "$host" in
      raw.githubusercontent.com|github.com|codeload.github.com|objects.githubusercontent.com|dl.google.com|maven.google.com|repo1.maven.org|repo.maven.apache.org|services.gradle.org|downloads.gradle.org) ;;
      *) block "network host '$host' is not in the allowlist." ;;
    esac
  done <<URLS
$urls
URLS
fi

# 6) Shell writes to protected config.
if [ "${FOLIO_UNLOCK_CONFIG:-0}" != "1" ]; then
  prot='(\.claude/hooks|\.claude/settings\.json|scripts/device/)'
  if printf '%s' "$cmd" | grep -Eq "(>>?|tee([[:space:]]+-a)?)[[:space:]]*[^[:space:];&|]*${prot}" \
    || printf '%s' "$cmd" | grep -Eq "(sed|perl)[[:space:]]+-[A-Za-z]*i[^;&|]*${prot}" \
    || printf '%s' "$cmd" | grep -Eq "(^|[;&|[:space:]])(mv|cp|rm|chmod|truncate|ln)[[:space:]][^;&|]*${prot}" \
    || printf '%s' "$cmd" | grep -Eq "git[[:space:]]+(checkout|restore)[^;&|]*${prot}"; then
    block "shell writes to protected config (.claude/hooks, .claude/settings.json, scripts/device) are not allowed. Record the exact diff as a Blocked item."
  fi
fi

exit 0
