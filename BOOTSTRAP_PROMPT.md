You are the sole engineer building **Folio**, a native Android note app, in this repository. The repository already contains the full specification and the operating system for this project:

- `CLAUDE.md` (already loaded): constraints, protocol, commands.
- `docs/plan/PLAN.md`: phase index, reading protocol, task format. `docs/plan/STATUS.md`: where you are.
- `docs/architecture/`: the design. `docs/architecture/decisions.md`: ADRs.
- `.claude/skills/next-task/SKILL.md`: the procedure for every task.

## This first session
1. Read `docs/plan/PLAN.md` and `docs/plan/STATUS.md`. Do not read other docs yet.
2. Verify kit integrity: every path in PLAN.md "Kit manifest" exists. If anything is missing, record it under Blocked in STATUS.md and stop.
3. Execute Phase 0 in order with the next-task skill, one task per commit.
4. When P00 is finished, perform the phase REVIEW step from the next-task skill (reviewer subagent, fixes, full `./gradlew qa`, tag `p00-done`).
5. Stop with a summary of at most 15 lines: what was done, device facts discovered, open Blocked and USER-CHECK items, and whether the repository is ready for `scripts/auto/run-loop.sh`.

## Standing rules (every session)
- Work from STATUS.md. Read only the current phase file and the doc sections listed in a task's `Read:` line.
- A task is done only when every acceptance item is verified by a command you ran. Summarize outputs; never paste large logs.
- Touch the tablet only through `scripts/device/*.sh`, and only the debug package.
- Needs a human (pairing, a tablet toggle, judging feel, latency, or looks)? Record it in STATUS.md (Blocked if it stops progress, USER-CHECK if it only needs later confirmation) and continue with the next unblocked task.
- Spec wrong or impossible? Do not deviate silently: add an entry to `docs/plan/AMENDMENTS.md` (date, task, change, reason), update the affected doc section, then proceed.
- Keep CLAUDE.md at or under 200 lines and use the maintain-memory skill when you learn something future sessions need.
