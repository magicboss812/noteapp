#!/usr/bin/env bash
# Folio phase runner (started by the USER, never by Claude).
# Runs the current PHASE (default) or a single TASK. Every task (and the phase REVIEW) gets its own
# fresh Claude Code session, so context never carries earlier tasks. When a usage limit hits, it waits
# until the announced reset time and resumes the SAME session of the task in progress.
# Blocked items in STATUS.md do not stop the run; Claude records them and moves to the next task.
# The run stops only when the phase (or task) is finished, a Blocked line carries [STOP], or sessions
# repeatedly end without progress.
#
# Usage:  bash scripts/auto/run-loop.sh [phase|task]        (default: phase)
# Resume after a crash, reboot or Ctrl+C: run the same command again. The run state in
# .device/loop-logs/run.state brings back the interrupted task's session with its full context.
# Env:
#   FOLIO_MODE=headless  (default) claude -p with a live progress feed in this terminal
#   FOLIO_MODE=rc        visible interactive session in this terminal + Remote Control
#                        (session end is detected heuristically: see watch_session)
#   FOLIO_EFFORT=high    effort level (low|medium|high|xhigh)
#   FOLIO_FEED=1         headless: 0 hides the live feed (only status lines and the final summary)
#   FOLIO_FRESH=0        1 ignores run.state and starts the current task in a new session
#   FOLIO_RESUME_SID=    resume this session id for the current task (overrides run.state)
#   FOLIO_LIMIT_POLL=900     seconds between checks when a limit has no readable reset time
#   FOLIO_LIMIT_MAX_WAIT=172800  give up if a single wait would exceed this (e.g. weekly limit)
#   FOLIO_RESET_BUFFER=90    seconds added after the announced reset time
#   FOLIO_IDLE_DONE=60       rc: quiet seconds after the task is done before closing the session
#   FOLIO_IDLE_STUCK=1200    rc: quiet seconds without progress before closing and resuming
# Keeps the machine awake via systemd-inhibit when available (Linux).
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
max_sessions=80
status_file="docs/plan/STATUS.md"
log_dir="$PWD/.device/loop-logs"
projects_dir="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/projects"
mkdir -p "$log_dir"
loop_log="$log_dir/loop.log"
sid_file="$log_dir/session.id"
state_file="$log_dir/run.state"
reset_file="$log_dir/.reset"
probe_out="$log_dir/.probe.txt"

case "$scope" in phase|task) ;; *) echo "usage: run-loop.sh [phase|task]"; exit 2 ;; esac
case "$mode" in headless|rc) ;; *) echo "FOLIO_MODE must be headless or rc"; exit 2 ;; esac
command -v jq >/dev/null 2>&1 || { echo "jq is required"; exit 2; }

say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$loop_log"; }
hash_status() { sha256sum "$status_file" | cut -d' ' -f1; }
field() { sed -nE "s/^$1: (.*)$/\1/p" "$status_file" | head -n1 | tr -d '\r'; }
section_lines() {   # $1 = section name prefix; prints its "- " items except "(none)"
  awk -v s="$1" '$0 ~ "^## "s {f=1;next} /^## /{f=0} f && /^- / && !/\(none\)/' "$status_file"
}
count_section() { section_lines "$1" | wc -l | tr -d ' '; }
stop_items() { section_lines Blocked | grep -F '[STOP]'; }
stop_count() { stop_items | wc -l | tr -d ' '; }

# ---------- run state (survives crashes and reboots) ----------
state_get() { [ -f "$state_file" ] && sed -nE "s/^$1=(.*)$/\1/p" "$state_file" | head -n1; }
save_state() {
  printf 'scope=%s\nphase=%s\ntask=%s\nsid=%s\nstart_head=%s\nupdated=%s\n' \
    "$scope" "$run_phase" "$cur_task" "$SID" "$start_head" "$(date '+%F %T')" > "$state_file"
  [ -n "$SID" ] && echo "$SID" > "$sid_file"
}

# ---------- goal ----------
run_phase="$(field phase)"
start_next="$(field next)"
phase_tag="$(printf '%s' "$run_phase" | tr 'A-Z' 'a-z')-done"

goal_reached() {
  local next; next="$(field next)"
  [ "$next" = "DONE" ] && return 0
  if [ "$scope" = "task" ]; then
    [ "$next" != "$start_next" ]
  else
    [ -n "$(git tag -l "$phase_tag")" ] || [ "$(field phase)" != "$run_phase" ]
  fi
}

new_prompt() {   # $1 = task id or "PNN REVIEW"
  printf '%s' "TASK RUN started by scripts/auto/run-loop.sh ($scope run, phase $run_phase). Execute exactly $1 with the next-task skill: verify, commit, update STATUS.md including next:. Anything that needs the user goes under Blocked and does not end the run: finish or skip what you can and advance next: as the skill says. Mark a Blocked line with [STOP] only when the skill says so. If git shows uncommitted changes, an earlier session was interrupted: inspect them first and continue that work. Finish with a summary of at most 5 lines."
}
continue_prompt() {   # $1 = task id
  printf '%s' "Resume the run where it stopped (paused by a usage limit, a turn limit, or a restart of the machine). Check git status and STATUS.md first, then finish $1 with the next-task skill as instructed at the start of this session."
}

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
# Strict on purpose: code and docs in tool output contain words like "presets" or "Arming resets".
limit_text() {
  printf '%s' "$1" | grep -iE "hit your [a-z ]*limit|usage limit reached|limit reached\|[0-9]|resets ([0-9]{1,2}(:[0-9]{2})? ?(am|pm)|[A-Z][a-z]{2} [0-9])" \
    | head -n1 | cut -c1-160
}

probe() {   # 0 = Claude answers; else unavailable (output kept in $probe_out)
  # Runs outside the repository so the probe loads no CLAUDE.md, hooks or STATUS snapshot.
  ( cd "${TMPDIR:-/tmp}" && timeout 180 claude -p "Reply with the single word OK." --max-turns 1 --effort low \
      --permission-mode dontAsk ) > "$probe_out" 2>&1 && grep -q "OK" "$probe_out"
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
      say "Limit resets too far ahead ($(date -d "@$ts" '+%a %d %b %H:%M')): ${msg}. Stopping; rerun the same command after the reset."
      exit 5
    fi
    say "Usage limit: ${msg:-limit}. Waiting until $(date -d "@$ts" '+%a %H:%M'), then continuing."
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
    "\n[session ended] " + (.subtype // "") + ", turns " + ((.num_turns // 0) | tostring) + "\n"
  else empty end'

run_headless() {   # $1 = new|resume ; sets RUN_TEXT (result and CLI lines), LAST_RESULT and SID
  local kind="$1" log args sid
  log="$log_dir/$(date +%Y%m%d-%H%M%S)-${cur_task// /_}.jsonl"
  if [ "$kind" = new ]; then args=(-p "$(new_prompt "$cur_task")"); else args=(-p "$(continue_prompt "$cur_task")" --resume "$SID"); fi
  if [ "$feed" = "1" ]; then
    claude "${args[@]}" --permission-mode dontAsk --effort "$effort" --max-turns 400 \
      --output-format stream-json --verbose 2>&1 | tee "$log" | jq -rj --unbuffered "$feed_filter" 2>/dev/null
  else
    claude "${args[@]}" --permission-mode dontAsk --effort "$effort" --max-turns 400 \
      --output-format stream-json --verbose > "$log" 2>&1
  fi
  sid=$(jq -r 'select(.session_id? != null) | .session_id' "$log" 2>/dev/null | tail -n1)
  [ -n "$sid" ] && SID="$sid"
  # Limit messages arrive as the session result or as plain CLI lines, never inside tool output.
  RUN_TEXT=$( { jq -r 'select(.type == "result") | .result // empty' "$log" 2>/dev/null; grep -v '^{' "$log"; } | tail -n 60 )
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

task_done() { goal_reached || [ "$(field next)" != "$cur_task" ]; }

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
    last=$started
    if [ -n "$tfile" ] && [ -f "$tfile" ]; then
      # Newest write of the transcript or of its subagent transcripts (reviewer, device-tester).
      last=$(find "$tfile" "${tfile%.jsonl}" -type f -printf '%T@\n' 2>/dev/null | sort -n | tail -n1 | cut -d. -f1)
      [ -n "$last" ] || last=$started
    fi
    idle=$((now - last))
    if task_done && [ "$idle" -ge "$idle_done" ]; then
      echo "$(date +%H:%M:%S) task finished: closing session" >> "$loop_log"; stop_pid "$pid"; return 0
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
  [ "$kind" = new ] && : > "$sid_file"
  watch_session "$pidfile" "$marker" < /dev/null &
  watcher=$!
  if [ "$kind" = new ]; then
    ( echo "$BASHPID" > "$pidfile"
      exec claude --remote-control "Folio $cur_task" --permission-mode dontAsk --effort "$effort" "$(new_prompt "$cur_task")" )
  else
    ( echo "$BASHPID" > "$pidfile"
      exec claude --resume "$SID" --remote-control "Folio $cur_task" --permission-mode dontAsk --effort "$effort" "$(continue_prompt "$cur_task")" )
  fi
  kill "$watcher" 2>/dev/null; wait "$watcher" 2>/dev/null
  stty sane 2>/dev/null || true
  [ -s "$sid_file" ] && SID="$(cat "$sid_file")"
  RUN_TEXT="$(cat "$reset_file" 2>/dev/null)"
  LAST_RESULT=""
}

# ---------- main ----------
if goal_reached; then echo "Nothing to do: goal already reached (next: $(field next))."; exit 0; fi
if [ "$(stop_count)" -gt 0 ]; then
  echo "STATUS.md has Blocked items marked [STOP]. Resolve them and remove the [STOP] mark, then rerun:"
  stop_items
  exit 3
fi

# Pick up an interrupted run: same phase and same task -> resume its session (full context).
SID=""; start_head="$(git rev-parse HEAD 2>/dev/null || echo none)"
if [ "${FOLIO_FRESH:-0}" != "1" ] && [ "$(state_get phase)" = "$run_phase" ]; then
  [ "$scope" = phase ] && [ "$(state_get scope)" = phase ] && start_head="$(state_get start_head)"
  [ "$(state_get task)" = "$start_next" ] && SID="$(state_get sid)"
fi
[ -n "${FOLIO_RESUME_SID:-}" ] && SID="$FOLIO_RESUME_SID"
cur_task="$start_next"
if [ -n "$SID" ]; then
  say "Run start: scope=$scope ($run_phase, next $cur_task) mode=$mode effort=$effort; resuming session $SID"
else
  say "Run start: scope=$scope ($run_phase, next $cur_task) mode=$mode effort=$effort"
fi

RUN_TEXT=""; LAST_RESULT=""; no_progress=0; sessions=0
while :; do
  cur_task="$(field next)"
  if [ "$(state_get task)" != "$cur_task" ] && [ -z "${FOLIO_RESUME_SID:-}" ]; then SID=""; fi
  unset FOLIO_RESUME_SID
  if [ -n "$SID" ]; then kind=resume; else kind=new; fi
  save_state
  head_before="$(git rev-parse HEAD 2>/dev/null || echo none)"
  status_before="$(hash_status)"
  if [ "$kind" = new ]; then say "Task $cur_task: new session."; fi
  if [ "$mode" = headless ]; then run_headless "$kind"; else run_rc "$kind"; fi
  save_state
  sessions=$((sessions + 1))

  if goal_reached; then break; fi
  if [ "$(stop_count)" -gt 0 ]; then say "Stopped: Blocked items marked [STOP] need you."; break; fi

  if [ -n "$(limit_text "$RUN_TEXT")" ]; then
    wait_for_limit "$RUN_TEXT"; no_progress=0
  elif [ "$(field next)" != "$cur_task" ]; then
    no_progress=0
    say "Task $cur_task finished; next $(field next)."
  elif ! probe; then
    wait_for_limit "$(cat "$probe_out")"; no_progress=0
  elif [ "$head_before" = "$(git rev-parse HEAD 2>/dev/null || echo none)" ] && [ "$status_before" = "$(hash_status)" ]; then
    no_progress=$((no_progress + 1))
    [ "$no_progress" -ge 3 ] && { say "Stopped: three sessions in a row without progress on $cur_task. See $loop_log and $log_dir."; break; }
    say "Session ended without progress on $cur_task ($no_progress/3). Resuming."
  else
    no_progress=0
    say "Session ended before $cur_task was finished (turn limit or early stop). Resuming the same session."
  fi
  [ "$sessions" -ge "$max_sessions" ] && { say "Stopped: $max_sessions sessions reached."; break; }
  if [ -z "$SID" ] && [ "$(field next)" = "$cur_task" ]; then say "No session id captured; starting a new session for $cur_task."; fi
done

# ---------- summary ----------
echo
echo "================ Folio run summary ================"
if goal_reached; then
  echo "Result: goal reached ($scope $( [ "$scope" = phase ] && echo "$run_phase" || echo "$start_next" ))"
  rm -f "$state_file"
else
  echo "Result: stopped before the goal. Rerun the same command to continue where it stopped."
fi
echo "Now: phase $(field phase), next $(field next)"
echo "Commits this run:"
git log --oneline "$start_head..HEAD" 2>/dev/null | sed 's/^/  /' | head -n 40
echo "Blocked: $(count_section Blocked) ([STOP]: $(stop_count))   USER-CHECK: $(count_section USER-CHECK)   (details in $status_file)"
[ -n "$LAST_RESULT" ] && { echo; echo "Claude's final message:"; printf '%s\n' "$LAST_RESULT"; }
[ -n "$SID" ] && echo "Last session: $SID  (open it with: claude --resume $SID)"
goal_reached && exit 0 || exit 4
