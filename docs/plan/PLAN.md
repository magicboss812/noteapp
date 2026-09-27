# Build plan

## Reading protocol (keeps context cheap)
1. STATUS.md (injected at session start; <= 60 lines).
2. The current phase file only: read its header once per session, then only your task block (Grep `^### PNN-TNN`, Read that range).
3. Only the anchors in the task's `Read:` line. Architecture files are split so one anchor is usually < 80 lines.
4. Nothing else unless a task is blocked by missing information.

## Phases
| Phase | File | Goal | Exit criteria |
|---|---|---|---|
| P00 | phase-00-bootstrap.md | Toolchain, device link, versions, skeleton, quality gates, test infra, debug automation | App launches on the tablet; `qa` green |
| P01 | phase-01-spikes.md | De-risk ink latency, tile strategy, text grid metrics, LaTeX, PDF export, storage IO, stylus capabilities | ADR-002..008 Accepted with evidence |
| P02 | phase-02-foundation.md | Model, commands/undo, `.folio` format, working copy + packer, library index, sessions | Round-trip, crash, and index tests green |
| P03 | phase-03-canvas-ink.md | Viewport, templates, tiles, brushes, wet/dry ink, erasers, stylus extras | 1500-stroke page within budgets on device |
| P04 | phase-04-editor-shell.md | Design system, editor screen, toolbar + docking, pages, undo UI, new-note flow, autosave | Draw, undo, reopen with persistence on device |
| P05 | phase-05-library.md | Library home, folders, favorites, tags, search, bin, transitions | Usable daily as an ink notebook |
| P06 | phase-06-text-engine.md | Grid-snapped Markdown text, flows across pages, fonts, LaTeX, tables, links | Grid invariant tests pass on 20 fonts; text budgets met |
| P07 | phase-07-objects.md | Shapes + recognition, lasso, images, sticky notes, ruler, attachments, note links | Every object op undoable and persisted |
| P08 | phase-08-pdf.md | PDF import, background rendering, blank-page insertion | 100-page PDF within budgets |
| P09 | phase-09-export.md | PDF, PNG, `.folio` export incl. PDF overlay merge and infinite crop | Exports verified by re-render tests |
| P10 | phase-10-infinite-split.md | Infinite canvas conversion and mode, split view panes | Both work on device |
| P11 | phase-11-polish.md | Settings, dark UI, motion pass, shortcuts, baseline profile, release build, hardening | All budgets met; USER-CHECK list empty |

Deferred scope: `BACKLOG.md`. Spec changes: `AMENDMENTS.md`.

## Task block format
```
### PNN-TNN Title
Implements: R-IDs from 01-requirements.md
Read: file.md#anchor, ...
Files: main paths (guidance, not a limit)
Do:
1. ...
Accept:
- [ ] unit: ...          (JVM/Robolectric test exists and passes)
- [ ] screenshot: ...    (Roborazzi golden)
- [ ] device: ...        (device-tester subagent)
- [ ] user: ...          (goes to STATUS USER-CHECK, non-blocking)
Verify: exact commands
```
Spike blocks (P01) use: Question, Options, Build, Measure, Decision rule, Output.

## STATUS format
```
# STATUS
phase: PNN
next: PNN-TNN | PNN REVIEW | DONE
updated: YYYY-MM-DD

## Completed
- P00 done YYYY-MM-DD (tag p00-done): <key facts, <= 1 line>

## Current phase progress
- PNN: T01 T02 ...

## Blocked (needs user; stops dependent tasks)
- <task-id>: <problem> -> <exact user action>

## USER-CHECK (human verification; non-blocking)
- <task-id>: <what to open/do> -> <what good looks like>

## Deferred (id: reason)
- D-001 <task-id>: <one clause>

## Handoff (<= 5 lines, overwritten each session)
- ...
```
Rules: a user answers a Blocked item by writing under it or deleting it; USER-CHECK lines get `-> ok` or `-> fail: ...` appended by the user. At session start, process answered items first (fail -> create a fix task note in Handoff and fix before continuing).

## Kit manifest (verified in the bootstrap session)
- CLAUDE.md, README.md, BOOTSTRAP_PROMPT.md, .gitignore, .gitattributes
- .claude/settings.json
- .claude/hooks/: session-context.sh, guard-bash.sh, guard-protected-files.sh, guard-memory-size.sh
- .claude/rules/: kotlin.md, architecture-boundaries.md, gradle.md, compose-ui.md, ink-and-input.md, rendering.md, text-engine.md, file-format-storage.md, pdf.md, testing.md, memory-files.md, docs.md
- .claude/skills/: next-task, device-test, spike, maintain-memory (each SKILL.md)
- .claude/agents/: reviewer.md, device-tester.md
- scripts/env/doctor.sh, scripts/auto/run-loop.sh
- scripts/device/: _common.sh, connect.sh, info.sh, install.sh, launch.sh, stop.sh, clear-data.sh, grant-storage.sh, screenshot.sh, input.sh, logcat.sh, gfxinfo.sh, diag.sh, debugcmd.sh, push-fixture.sh, pull.sh, wipe-debug-library.sh, instrumented.sh
- docs/architecture/: 00-overview.md .. 12-performance.md, decisions.md
- docs/plan/: PLAN.md, STATUS.md, AMENDMENTS.md, BACKLOG.md, phase-00 .. phase-11
- docs/notes/: env.md, device.md, perf.md, gotchas.md
- docs/design/reference/: editor-tools-reference.png, library-reference.png, README.md
- testdata/README.md
