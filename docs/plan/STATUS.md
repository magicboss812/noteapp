# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P00
next: P00-T06
updated: 2026-09-27

## Completed
- (none)

## Current phase progress
- P00: T01 T02 T03 T04 T05

## Blocked (needs user; stops dependent tasks)
- P00-T06: adb install refused (INSTALL_FAILED_USER_RESTRICTED); blocks device checks of T06-T08. -> on the tablet: Developer options > enable "Install via USB" (and "USB debugging (Security settings)"), keep it unlocked, tap Allow on install prompts; then delete this line.

## USER-CHECK (human verification; non-blocking)
- (none)

## Deferred (id: reason)
- (none)

## Handoff (<= 5 lines, overwritten each session)
- T01-T05 done. T06 code + JVM/screenshot tests committed; its device item (instrumented smoke test) waits on the Blocked install toggle.
- Next: after unblock, `bash scripts/device/instrumented.sh :app` (check result XML), mark T06 done; then T07.
