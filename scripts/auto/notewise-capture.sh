#!/usr/bin/env bash
# Notewise reference capture runner (started by the USER, never by Claude).
# One fresh Claude session per group of docs/design/notewise/CAPTURE.md (G1..G6: drive Notewise on the
# tablet, screenshot, measure, write notes/<group>.md), then the synthesis session S (writes DESIGN.md
# from the notes). Fresh sessions keep screenshots from piling up in one context window.
# Finished groups are skipped, so rerunning the same command resumes after a limit, crash or Ctrl+C.
# Before: tablet connected (scripts/device/connect.sh works) and unlocked, Notewise installed and opened once.
# Usage:  bash scripts/auto/notewise-capture.sh            (every unfinished group, then S)
#         bash scripts/auto/notewise-capture.sh G3 G4      (only these; delete notes/G3.md to redo G3)
# Env:    FOLIO_CAPTURE_MODEL=claude-opus-5-5  FOLIO_CAPTURE_EFFORT=high  FOLIO_FEED=1 (0: quiet)
#         NOTEWISE_PKG=<package> if nw.sh cannot find exactly one Notewise package
set -u
cd "$(dirname "$0")/../.." || exit 1

dir="docs/design/notewise"
model="${FOLIO_CAPTURE_MODEL:-claude-opus-5-5}"
effort="${FOLIO_CAPTURE_EFFORT:-high}"
feed="${FOLIO_FEED:-1}"
log_dir="$PWD/.device/loop-logs"
mkdir -p "$log_dir"
export CLAUDE_CODE_AUTO_COMPACT_WINDOW="${FOLIO_COMPACT_WINDOW:-200000}"
command -v claude >/dev/null 2>&1 || { echo "claude CLI not found"; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "jq is required"; exit 2; }
[ -f "$dir/CAPTURE.md" ] || { echo "missing $dir/CAPTURE.md"; exit 2; }

say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$log_dir/notewise-capture.log"; }
output_of() { if [ "$1" = S ]; then echo "$dir/DESIGN.md"; else echo "$dir/notes/$1.md"; fi; }

if [ "$#" -gt 0 ]; then groups=("$@"); else
  mapfile -t groups < <(sed -nE 's/^## (G[0-9]+|S) .*/\1/p' "$dir/CAPTURE.md")
fi

prompt() {
  printf '%s' "NOTEWISE CAPTURE started by scripts/auto/notewise-capture.sh. This is not a plan task: ignore STATUS.md and the next-task skill. Read docs/design/notewise/CAPTURE.md and execute exactly group $1 as it describes (S = synthesis without the device). Follow its context-economy and safety rules. When $(output_of "$1") is written: git add docs/design/notewise && git commit -m \"docs(design): Notewise capture $1 [P04-T10]\". Final message at most 5 lines."
}

feed_filter='
  if .type == "assistant" then
    (.message.content[]? |
      if .type == "text" then "\n" + .text + "\n"
      elif .type == "tool_use" then
        "  > " + .name + " " +
        ((.input.command // .input.file_path // .input.pattern // .input.description // "")
          | tostring | gsub("\n"; " ") | .[0:140]) + "\n"
      else empty end)
  elif .type == "result" then
    "\n[session ended] " + (.subtype // "") + ", turns " + ((.num_turns // 0) | tostring) + "\n"
  else empty end'

connected=0
for g in "${groups[@]}"; do
  case "$g" in G[0-9]*|S) ;; *) echo "unknown group $g (G1..G6 or S)"; exit 2 ;; esac
  out="$(output_of "$g")"
  if [ -f "$out" ]; then say "Group $g: done already ($out), skipping."; continue; fi
  if [ "$g" != S ] && [ "$connected" = 0 ]; then
    bash scripts/device/connect.sh || { say "Tablet not reachable; connect it and rerun."; exit 3; }
    bash scripts/design/nw.sh info || { say "nw.sh cannot see Notewise; see its message and rerun."; exit 3; }
    connected=1
  fi
  log="$log_dir/$(date +%Y%m%d-%H%M%S)-notewise-$g.jsonl"
  say "Group $g: new session ($model, $effort)."
  if [ "$feed" = 1 ]; then
    claude -p "$(prompt "$g")" --model "$model" --effort "$effort" --permission-mode dontAsk --max-turns 300 \
      --output-format stream-json --verbose < /dev/null 2>&1 | tee "$log" | jq -rj --unbuffered "$feed_filter" 2>/dev/null
  else
    claude -p "$(prompt "$g")" --model "$model" --effort "$effort" --permission-mode dontAsk --max-turns 300 \
      --output-format stream-json --verbose < /dev/null > "$log" 2>&1
  fi
  if [ ! -f "$out" ]; then
    say "Group $g ended without $out (limit, turn cap, or a refusal in the notes). Log: $log. Rerun the same command."
    exit 4
  fi
  say "Group $g finished."
done

if [ -f "$dir/DESIGN.md" ]; then
  say "Capture complete: $dir/DESIGN.md. Push the commits, then remove [STOP] from the P04-T10 line in docs/plan/STATUS.md."
fi
