# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P00
next: P00-T01
updated: 2026-09-26

## Completed
- (none)

## Current phase progress
- P00: (none)

## Blocked (needs user; stops dependent tasks)
- P00-T01: /opt/android-sdk is root:root (sdkmanager cannot even list), SDK licenses not accepted, no platform installed. Blocks T04-T08 (builds). -> run: `sudo chown -R $USER:$USER /opt/android-sdk && sdkmanager --licenses` (accept yourself), then delete this line. Claude installs `platforms;android-37.2` afterwards.

## USER-CHECK (human verification; non-blocking)
- (none)

## Deferred (id: reason)
- (none)

## Handoff (<= 5 lines, overwritten each session)
- Fresh kit imported. Start with P00-T01 (environment doctor).
