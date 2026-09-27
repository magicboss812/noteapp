---
name: next-task
description: Execute exactly one task from docs/plan/STATUS.md end to end (load minimal spec, implement, verify, commit, update status), or run the phase REVIEW step. Use for all plan work in this repository.
---
# next-task

## 1. Select
- Take `next:` from the STATUS snapshot. `next: DONE` -> report completion and stop.
- `next: PNN REVIEW` -> go to section 8.
- Find the task block: Grep `^### <task-id>` in the current phase file, then Read only that block (offset/limit). Read the phase file header once per session for its goal and exit criteria.
- If the task depends on an unresolved Blocked item, take the next unblocked task in the same phase and note the skip in Handoff.

## 2. Load minimal context
- Read only the anchors in the task's `Read:` line (Grep the heading, Read that section).
- Read the code you will change. Use narrow Glob/Grep. Never read whole directories "to get a feel".

## 3. Plan briefly
- List files to create/modify and the tests you will add. If the change exceeds ~12 files or ~800 lines, split into sub-steps committed as `<task-id>a`, `b`, ... keeping the build green after each.

## 4. Implement
- Rules for the files you touch load automatically; follow them.
- Write tests alongside the code. For bugs: failing test first.

## 5. Verify
- Run every command in the task's `Verify:` line.
- Then for each touched module: `./gradlew :<m>:spotlessCheck :<m>:detekt :<m>:lintDebug :<m>:testDebugUnitTest` (JVM modules: `:<m>:test`, no lint).
- `device:` items: call the `device-tester` subagent with task id, build command, and a numbered list of checks (action, expected result, budget). Use its report as evidence.
- `user:` items: add to STATUS `USER-CHECK` as one line: `<task-id>: <what to open/do> -> <what good looks like>`. They do not block completion.
- If a check fails: fix and re-run. After 3 focused attempts without progress: section 9.

## 6. Commit
- `git add -A` then `git commit` with message `<type>(<scope>): <summary> [<task-id>]`.
- Body: changes in 1-5 lines; verification commands with results; `goldens updated: ...` if any; `amendments: ...` if any.

## 7. Update STATUS.md (same commit when possible)
- `next:` = following task id in phase-file order; after the last task: `next: PNN REVIEW`.
- Append the task id to the current phase progress line.
- Overwrite `Handoff` (<= 5 lines): state, anything half-done, the very next step.
- Keep STATUS <= 60 lines.

## 8. Phase REVIEW
1. Invoke the `reviewer` subagent with range `p<previous>-done..HEAD` (P00: whole history).
2. Fix every blocking finding (commits tagged `[PNN-REVIEW]`). Non-blocking findings: fix if cheap, else STATUS Deferred with id.
3. Run the full `./gradlew qa`. Run the maintain-memory compaction procedure.
4. `git tag pNN-done`. Collapse the phase to one line under `Completed` (date, tag, key numbers). Set `next:` to the first task of the next phase.

## 9. Stop conditions
- Headless session (started by run-loop.sh): stop after one task or one REVIEW, final message <= 3 lines.
- Interactive session: continue to the next task unless the user said otherwise; after an auto-compaction or 3 completed tasks, write Handoff and stop.
- Stuck after 3 attempts: record under `Blocked` (what failed, what was tried, what is needed), commit partial work on branch `wip/<task-id>` (not on main), switch back to main, continue with an independent task or stop.
