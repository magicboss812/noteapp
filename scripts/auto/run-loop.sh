#!/usr/bin/env bash
# Folio phase runner (started by the USER, never by Claude).
# Runs the current PHASE (default) or a single TASK in ONE Claude Code session. When a usage limit
# hits, it waits until the announced reset time and resumes the SAME session. It stops only when the
# phase (or task) is finished, something is Blocked, or the session repeatedly stops without progress.
#
# Usage:  bash scripts/auto/run-loop.sh [phase|task]        (default: phase)
# Env:
#   FOLIO_MODE=headless  (default) claude -p with a live progress feed in this terminal
#   FOLIO_MODE=rc        visible interactive session in this terminal + Remote Control
#                        (session end is detected heuristically: see watch_session)
#   FOLIO_EFFORT=high    effort level (low|medium|high|xhigh)
#   FOLIO_FEED=1         headless: 0 hides the live feed (only status lines and the final summary)
#   FOLIO_LIMIT_POLL=900     seconds between checks when a limit has no readable reset time
#   FOLIO_LIMIT_MAX_WAIT=172800  give up if a single wait would exceed this (e.g. weekly limit)
#   FOLIO_RESET_BUFFER=90    seconds added after the announced reset time
#   FOLIO_IDLE_DONE=60       rc: quiet seconds after the goal is reached before closing the session
#   FOLIO_IDLE_STUCK=1200    rc: quiet seconds without progress before closing and resuming
# Keeps the machine awake via systemd-inhibit when available (Linux). Session id of the current run:
# .device/loop-logs/session.id  (resume manually with: claude --resume "$(cat .device/loop-logs/session.id)")
set -u
cd "$(dirname "$0")/../.." || exit 1

# Keep the laptop awake (idle, sleep, lid switch) for the whole run.
if [ -z "${FOLIO_INHIBITED:-}" ] && command -v systemd-inhibit >/dev/null 2>&1 \
   && systemd-inhibit --what=idle --who=Folio --why=check true >/dev/null 2>&1; then
  export FOLIO_INHIBITED=1
  exec systemd-inhibit --what=idle:sleep:handle-lid-switch --who="Folio" \
    --why="Folio build run" bash "$0" "$@"
fi

scope="${1:-phase}"
mode="${FOLIO_MODE:-headless}"
effort="${FOLIO_EFFORT:-high}"
feed="${FOLIO_FEED:-1}"
limit_poll="${FOLIO_LIMIT_POLL:-900}"
limit_max_wait="${FOLIO_LIMIT_MAX_WAIT:-172800}"
reset_buffer="${FOLIO_RESET_BUFFER:-90}"
idle_done="${FOLIO_IDLE_DONE:-60}"
idle_stuck="${FOLIO_IDLE_STUCK:-1200}"
probe_idle=300
max_resumes=50
status_file="docs/plan/STATUS.md"
log_dir=".device/loop-logs"
projects_dir="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/projects"
mkdir -p "$log_dir"
loop_log="$log_dir/loop.log"
sid_file="$log_dir/session.id"
reset_file="$log_dir/.reset"
probe_out="$log_dir/.probe.txt"

case "$scope" in phase|task) ;; *) echo "usage: run-loop.sh [phase|task]"; exit 2 ;; esac
case "$mode" in headless|rc) ;; *) echo "FOLIO_MODE must be headless or rc"; exit 2 ;; esac
command -v jq >/dev/null 2>&1 || { echo "jq is required"; exit 2; }

say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$loop_log"; }
hash_status() { sha256sum "$status_file" | cut -d' ' -f1; }
field() { sed -nE "s/^$1: (.*)$/\1/p" "$status_file" | head -n1; }
blocked_count() {
  awk '/^## Blocked/{f=1;next} /^## /{f=0} f && /^- / && !/\(none\)/ && !/\[Checked\][ \t\r]*$/{c++} END{print c+0}' "$status_file"
}
count_section() {
  awk -v s="$1" '$0 ~ "^## "s {f=1;next} /^## /{f=0} f && /^- / && !/\(none\)/{c++} END{print c+0}' "$status_file"
}

# ---------- goal ----------
start_phase="$(field phase)"
start_next="$(field next)"
start_head="$(git rev-parse HEAD 2>/dev/null || echo none)"
phase_tag="$(printf '%s' "$start_phase" | tr 'A-Z' 'a-z')-done"

goal_reached() {
  local next; next="$(field next)"
  [ "$next" = "DONE" ] && return 0
  if [ "$scope" = "task" ]; then
    [ "$next" != "$start_next" ]
  else
    [ -n "$(git tag -l "$phase_tag")" ] || [ "$(field phase)" != "$start_phase" ]
  fi
}

if [ "$scope" = "phase" ]; then
  PROMPT="PHASE RUN started by scripts/auto/run-loop.sh. Complete all remaining tasks of phase $start_phase in this one session with the next-task skill (one commit per task), then its phase REVIEW (reviewer subagent, fixes, full qa, tag $phase_tag). For this session the stop rules in CLAUDE.md session protocol step 9 and in the next-task skill section 9 do NOT apply: keep working until $phase_tag exists or Blocked items stop every remaining task of the phase. Auto-compaction is fine; STATUS.md and git hold the state. If git shows uncommitted changes, an earlier session was interrupted (for example by a usage limit): inspect them first and continue that task. Finish with a summary of at most 10 lines: tasks done, key numbers, new USER-CHECK and Blocked items."
else
  PROMPT="TASK RUN started by scripts/auto/run-loop.sh. Execute exactly task $start_next with the next-task skill: verify, commit, update STATUS.md. If git shows uncommitted changes, an earlier session was interrupted: inspect them first and continue that task. Finish with a summary of at most 5 lines."
fi
CONTINUE="Resume the run from where it stopped (it was paused, for example by a usage limit or a turn limit). Check git status and STATUS.md first, then continue with the same goal: $( [ "$scope" = phase ] && echo "finish phase $start_phase including its REVIEW and tag $phase_tag" || echo "finish task $start_next" )."

# ---------- usage limits ----------
# Prints the reset time as epoch seconds for texts like
#   "You've hit your session limit · resets 6:20pm (Europe/Berlin)"   "resets Oct 3, 9am (Europe/Berlin)"
#   "Claude AI usage limit reached|1790530000"
parse_reset() {
  local text="$1" line tz when ts now
  now=$(date +%s)
  ts=$(printf '%s' "$text" | grep -oE 'limit reached\|[0-9]{10}' | head -n1 | cut -d'|' -f2)
  if [ -z "$ts" ]; then
    line=$(printf '%s' "$text" | grep -oE 'resets [^"|]*' | head -n1)
    [ -n "$line" ] || return 1
    tz=$(printf '%s' "$line" | sed -nE 's/.*\(([^)]+)\).*/\1/p')
    when=$(printf '%s' "$line" | sed -E 's/^resets (at )?//; s/ *\(.*$//; s/,/ /g; s/ at / /g')
    if [ -n "$tz" ]; then ts=$(TZ="$tz" date -d "$when" +%s 2>/dev/null); else ts=$(date -d "$when" +%s 2>/dev/null); fi
    [ -n "$ts" ] || return 1
    [ "$ts" -le "$now" ] && ts=$((ts + 86400))   # time-only formats mean the next occurrence
  fi
  echo "$ts"
}
limit_text() { printf '%s' "$1" | grep -iE "hit your .*limit|usage limit|limit reached|rate limit|resets " | head -n1 | cut -c1-160; }

probe() {   # 0 = Claude answers; else unavailable (output kept in $probe_out)
  timeout 180 claude -p "Reply with the single word OK." --max-turns 1 --effort low \
    --permission-mode dontAsk > "$probe_out" 2>&1 && grep -q "OK" "$probe_out"
}

sleep_until() {   # robust against laptop suspend: checks the wall clock every minute
  local target="$1"
  while [ "$(date +%s)" -lt "$target" ]; do sleep 60; done
}

wait_for_limit() {   # $1 = text that may contain the limit message
  local ts msg
  msg="$(limit_text "$1")"
  if ts=$(parse_reset "$1"); then
    ts=$((ts + reset_buffer))
    if [ $((ts - $(date +%s))) -gt "$limit_max_wait" ]; then
      say "Limit resets too far ahead ($(date -d "@$ts" '+%a %d %b %H:%M')): ${msg}. Stopping; rerun after the reset."
      exit 5
    fi
    say "Usage limit: ${msg:-limit}. Waiting until $(date -d "@$ts" '+%a %H:%M'), then resuming the same session."
    sleep_until "$ts"
  else
    say "Claude unavailable (${msg:-no reset time given}). Checking again every $((limit_poll / 60)) min."
    sleep "$limit_poll"
    until probe; do
      if ts=$(parse_reset "$(cat "$probe_out")"); then wait_for_limit "$(cat "$probe_out")"; return; fi
      sleep "$limit_poll"
    done
  fi
  say "Resuming."
}

# ---------- headless session ----------
feed_filter='
  if .type == "assistant" then
    (.message.content[]? |
      if .type == "text" then "\n" + .text + "\n"
      elif .type == "tool_use" then
        "  > " + .name + " " +
        ((.input.command // .input.file_path // .input.pattern // .input.url // .input.description // "")
          | tostring | gsub("\n"; " ") | .[0:140]) + "\n"
      else empty end)
  elif .type == "result" then
    "\n[run ended] " + (.subtype // "") + ", turns " + ((.num_turns // 0) | tostring) + "\n"
  else empty end'

run_headless() {   # $1 = new|resume ; sets RUN_TEXT (all strings of the run) and SID
  local kind="$1" log args
  log="$log_dir/$(date +%Y%m%d-%H%M%S)-${start_next// /_}.jsonl"
  if [ "$kind" = new ]; then args=(-p "$PROMPT"); else args=(-p "$CONTINUE" --resume "$SID"); fi
  if [ "$feed" = "1" ]; then
    claude "${args[@]}" --permission-mode dontAsk --effort "$effort" --max-turns 400 \
      --output-format stream-json --verbose 2>&1 | tee "$log" | jq -rj --unbuffered "$feed_filter" 2>/dev/null
  else
    claude "${args[@]}" --permission-mode dontAsk --effort "$effort" --max-turns 400 \
      --output-format stream-json --verbose > "$log" 2>&1
  fi
  local sid
  sid=$(jq -r 'select(.session_id? != null) | .session_id' "$log" 2>/dev/null | tail -n1)
  [ -n "$sid" ] && SID="$sid" && echo "$SID" > "$sid_file"
  RUN_TEXT=$( { jq -r '.. | strings' "$log" 2>/dev/null; grep -v '^{' "$log"; } | tail -n 400 )
  LAST_RESULT=$(jq -r 'select(.type == "result") | .result // empty' "$log" 2>/dev/null | tail -n1)
}

# ---------- rc session (interactive + Remote Control) ----------
stop_pid() {
  local pid="$1"
  if [ -r "/proc/$pid/winpid" ] && command -v taskkill >/dev/null 2>&1; then
    taskkill //PID "$(cat "/proc/$pid/winpid")" //T //F >/dev/null 2>&1
  else
    kill -INT "$pid" 2>/dev/null; sleep 2; kill -INT "$pid" 2>/dev/null; sleep 5
    pkill -TERM -P "$pid" 2>/dev/null; kill -TERM "$pid" 2>/dev/null
  fi
}

watch_session() {   # background: finds this session's transcript, ends the session when appropriate
  local pidfile="$1" marker="$2" started pid="" tfile="" now last idle last_probe
  started=$(date +%s); last_probe=$started
  rm -f "$reset_file"
  while :; do
    sleep 15
    [ -z "$pid" ] && [ -s "$pidfile" ] && pid=$(cat "$pidfile")
    [ -n "$pid" ] || continue
    kill -0 "$pid" 2>/dev/null || return 0
    now=$(date +%s)
    if [ -z "$tfile" ] && [ $((now - started)) -le 240 ]; then   # before any probe can run
      tfile=$(find "$projects_dir" -name '*.jsonl' -newer "$marker" -printf '%T@ %p\n' 2>/dev/null | sort -n | tail -n1 | cut -d' ' -f2-)
      [ -n "$tfile" ] && basename "$tfile" .jsonl > "$sid_file"
    fi
    last=$started; [ -n "$tfile" ] && [ -f "$tfile" ] && last=$(date -r "$tfile" +%s)
    idle=$((now - last))
    if goal_reached && [ "$idle" -ge "$idle_done" ]; then
      echo "$(date +%H:%M:%S) goal reached: closing session" >> "$loop_log"; stop_pid "$pid"; return 0
    fi
    if [ "$idle" -ge "$probe_idle" ] && [ $((now - last_probe)) -ge "$probe_idle" ]; then
      last_probe=$now
      if ! probe; then
        cp "$probe_out" "$reset_file"
        echo "$(date +%H:%M:%S) session quiet and Claude unavailable: closing session" >> "$loop_log"
        stop_pid "$pid"; return 0
      fi
    fi
    if [ "$idle" -ge "$idle_stuck" ]; then
      echo "$(date +%H:%M:%S) session quiet ${idle}s: closing it to resume" >> "$loop_log"
      stop_pid "$pid"; return 0
    fi
  done
}

run_rc() {   # $1 = new|resume
  local kind="$1" pidfile="$log_dir/.session.pid" marker="$log_dir/.session-start" watcher
  rm -f "$pidfile"; touch "$marker"
  watch_session "$pidfile" "$marker" < /dev/null &
  watcher=$!
  if [ "$kind" = new ]; then
    ( echo "$BASHPID" > "$pidfile"
      exec claude --remote-control "Folio $start_next" --permission-mode dontAsk --effort "$effort" "$PROMPT" )
  else
    ( echo "$BASHPID" > "$pidfile"
      exec claude --resume "$SID" --remote-control "Folio $start_next" --permission-mode dontAsk --effort "$effort" "$CONTINUE" )
  fi
  kill "$watcher" 2>/dev/null; wait "$watcher" 2>/dev/null
  stty sane 2>/dev/null || true
  [ -s "$sid_file" ] && SID="$(cat "$sid_file")"
  RUN_TEXT="$(cat "$reset_file" 2>/dev/null)"
  LAST_RESULT=""
}

# ---------- main ----------
if goal_reached; then echo "Nothing to do: goal already reached (next: $(field next))."; exit 0; fi
if [ "$(blocked_count)" -gt 0 ]; then echo "Blocked items present in STATUS.md. Resolve them, then rerun."; exit 3; fi
say "Run start: scope=$scope ($start_phase, next $start_next) mode=$mode effort=$effort"

SID="${FOLIO_RESUME_SID:-}"; RUN_TEXT=""; LAST_RESULT=""; kind=new; no_progress=0; resumes=0
[ -n "$SID" ] && kind=resume
while :; do
  head_before="$(git rev-parse HEAD 2>/dev/null || echo none)"
  status_before="$(hash_status)"
  if [ "$mode" = headless ]; then run_headless "$kind"; else run_rc "$kind"; fi
  [ -n "$SID" ] && kind=resume

  if goal_reached; then break; fi
  if [ "$(blocked_count)" -gt 0 ]; then say "Stopped: Blocked items in STATUS.md need you."; break; fi

  if [ -n "$(limit_text "$RUN_TEXT")" ]; then
    wait_for_limit "$RUN_TEXT"; no_progress=0
  elif ! probe; then
    wait_for_limit "$(cat "$probe_out")"; no_progress=0
  elif [ "$head_before" = "$(git rev-parse HEAD 2>/dev/null || echo none)" ] && [ "$status_before" = "$(hash_status)" ]; then
    no_progress=$((no_progress + 1))
    say "Session ended without progress ($no_progress/3). Resuming."
    [ "$no_progress" -ge 3 ] && { say "Stopped: three sessions in a row without progress. See $loop_log and $log_dir."; break; }
  else
    no_progress=0
    say "Session ended before the goal (turn limit or early stop). Resuming the same session."
  fi
  resumes=$((resumes + 1))
  [ "$resumes" -ge "$max_resumes" ] && { say "Stopped: $max_resumes resumes reached."; break; }
  [ -z "$SID" ] && { say "No session id captured; starting a new session instead of resuming."; kind=new; }
done

# ---------- summary ----------
echo
echo "================ Folio run summary ================"
if goal_reached; then echo "Result: goal reached ($scope $( [ "$scope" = phase ] && echo "$start_phase" || echo "$start_next" ))"; else echo "Result: stopped before the goal"; fi
echo "Now: phase $(field phase), next $(field next)"
echo "Commits this run:"
git log --oneline "$start_head..HEAD" 2>/dev/null | sed 's/^/  /' | head -n 40
echo "Blocked: $(blocked_count)   USER-CHECK: $(count_section USER-CHECK)   (details in $status_file)"
[ -n "$LAST_RESULT" ] && { echo; echo "Claude's final message:"; printf '%s\n' "$LAST_RESULT"; }
[ -n "$SID" ] && echo "Session: $SID  (open it with: claude --resume $SID)"
goal_reached && exit 0 || exit 4