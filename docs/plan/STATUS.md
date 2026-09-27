# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P00
next: P00-T04
updated: 2026-09-27

## Completed
- (none)

## Current phase progress
- P00: T02 T03

## Blocked (needs user; stops dependent tasks)
- P00-T01: /opt/android-sdk is root:root (sdkmanager cannot even list), SDK licenses not accepted, no platform installed. Blocks T04-T08 (builds). -> run: `sudo chown -R $USER:$USER /opt/android-sdk && sdkmanager --licenses` (accept yourself), then delete this line. Claude installs `platforms;android-37.2` afterwards.

## USER-CHECK (human verification; non-blocking)
- (none)

## Deferred (id: reason)
- (none)

## Handoff (<= 5 lines, overwritten each session)
- T01 blocked (SDK perms/licenses); T02 done (SDK 36, minSdk 35); T03 done (AGP 9.4.1, Kotlin 2.4.20, A-001).
- Next: T04 skeleton; needs the T01 unblock, then install platforms;android-37.0 (and 37.2) and finish T01.
