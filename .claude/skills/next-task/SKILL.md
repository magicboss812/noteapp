---
name: next-task
description: Execute exactly one task from docs/plan/STATUS.md end to end (load minimal spec, implement, verify, commit, update status), or run the phase REVIEW step. Use for all plan work in this repository.
---
# next-task

## 1. Select
- Take `next:` from the STATUS snapshot. `next: DONE` -> report completion and stop.
- `next: PNN REVIEW` -> go to section 8.
- Find the task block: Grep `^### <task-id>` in the current phase file, then Read only that block (offset/limit). Read the phase file header once per session for its goal and exit criteria.
- If the task depends on an unresolved Blocked item, do not wait: set `next:` to the following unblocked task (section 7), note the skip in Handoff, commit STATUS, and stop (headless) or continue with it (interactive).

## 2. Load minimal context
- Read only the anchors in the task's `Read:` line (Grep the heading, Read that section).
- Read the code you will change. Use narrow Glob/Grep. Never read whole directories "to get a feel".
- Appending to `docs/plan/AMENDMENTS.md` or `docs/architecture/decisions.md`: Grep the last entry id, Read only the last ~30 lines (offset), then Edit after them.

## 3. Plan briefly
- List files to create/modify and the tests you will add. If the change exceeds ~12 files or ~800 lines, split into sub-steps committed as `<task-id>a`, `b`, ... keeping the build green after each.

## 4. Implement
- Rules for the files you touch load automatically; follow them.
- Write tests alongside the code. For bugs: failing test first.

## 5. Verify
- Headless Bash is denied (a wasted turn each) for: a `cd` prefix (the shell already sits in the repo root), `>`/`>>` redirects (also to /tmp), `for`/`while` loops, `sed -i`, `python3`, `xargs`, `rmdir`, `jar`. Run one plain command and pipe long output: `./gradlew qa --quiet --console=plain 2>&1 | tail -n 80`.
- Run every command in the task's `Verify:` line.
- Then for each touched module: `./gradlew :<m>:spotlessCheck :<m>:detekt :<m>:lintDebug :<m>:testDebugUnitTest` (JVM modules: `:<m>:test`, no lint).
- `device:` items: call the `device-tester` subagent with task id, build command, and a numbered list of checks (action, expected result, budget). Use its report as evidence.
- `user:` items: add to STATUS `USER-CHECK` as one line: `<task-id>: <what to open/do> -> <what good looks like>`. They do not block completion.
- If a check fails: fix and re-run. After 3 focused attempts without progress: section 9.

## 6. Commit
- `git add -A` then `git commit` with message `<type>(<scope>): <summary> [<task-id>]`.
- Body: changes in 1-5 lines; verification commands with results; `goldens updated: ...` if any; `amendments: ...` if any.

## 7. Update STATUS.md (same commit when possible)
- `next:` = following task id in phase-file order that is not waiting on a Blocked item. After the last task: an earlier skipped task whose Blocked item the user has answered, else `next: PNN REVIEW`.
- Append the task id to the current phase progress line; a skipped or partly done task as `T06 (blocked)`.
- Blocked items never stop the unattended run by themselves. Add `[STOP]` at the end of a Blocked line only when nothing else can move: every remaining task of the phase waits on the user, or the REVIEW cannot close the phase (section 8). The run-loop halts on `[STOP]` lines only.
- Overwrite `Handoff` (<= 5 lines): state, anything half-done, the very next step.
- Keep STATUS <= 60 lines.

## 8. Phase REVIEW
0. Process answered Blocked items first. A task marked `(blocked)` whose work is still missing (code, tests, or an exit-criterion check), with no answer: add `PNN REVIEW: phase cannot close until <ids> -> <user action> [STOP]` under Blocked, commit, stop. Open items that only need the user to verify something later (like a device-only look) do not stop the REVIEW; they stay in Blocked or USER-CHECK.
1. Invoke the `reviewer` subagent with range `p<previous>-done..HEAD` (P00: whole history). Tell it to skip `./gradlew qa`: step 3 runs it after the fixes.
2. Fix every blocking finding (commits tagged `[PNN-REVIEW]`). Non-blocking findings: fix if cheap, else STATUS Deferred with id. A blocking finding that needs a user decision: Blocked item with `[STOP]`, commit, stop.
3. Run the full `./gradlew qa`. Run the maintain-memory compaction procedure. qa still red after 3 focused attempts: Blocked item with `[STOP]`, commit, stop.
4. `git tag pNN-done`. Collapse the phase to one line under `Completed` (date, tag, key numbers). Set `next:` to the first task of the next phase.

## 9. Stop conditions
- Headless session (started by run-loop.sh): stop after one task or one REVIEW, final message <= 5 lines. The loop starts the next task in a fresh session; STATUS Handoff is the only carry-over, so write it for a reader with no context.
- Interactive session: continue to the next task unless the user said otherwise; after an auto-compaction or 3 completed tasks, write Handoff and stop.
- Stuck after 3 attempts: record under `Blocked` (what failed, what was tried, what is needed), commit partial work on branch `wip/<task-id>`, switch back to the working branch, advance `next:` per section 7, commit STATUS there, and stop (headless) or continue (interactive).
