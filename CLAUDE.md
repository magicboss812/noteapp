# Folio

Native Android hybrid note app (pen ink + grid-snapped Markdown text) for one device: Xiaomi Pad 7 (HyperOS, 3200x2136, 144 Hz, Xiaomi Focus Pen, Bluetooth keyboard). Personal, offline, sideloaded, English UI. The user steers and tests; you design details, build, verify.

<!-- Maintainers: this file is capped at 200 lines by .claude/hooks/guard-memory-size.sh. HTML comments are stripped from context. -->

## Hard constraints (never violate)
- Offline: no INTERNET or network-state permission in any merged manifest (`./gradlew verifyNoInternet`). No network code, analytics, or crash-reporting SDKs.
- Tablet access only via `bash scripts/device/*.sh`. Raw `adb`/`fastboot` is blocked by hook.
- Only `dev.folio.notes.debug` (and later `dev.folio.notes.benchmark`) may be installed, launched, cleared, or tested. Only `/sdcard/Documents/Folio-Debug/` may be written.
- Never touch the release package `dev.folio.notes` or `Documents/Folio/` on the device.
- Pen input never triggers Compose recomposition per motion event. Wet ink = androidx.ink InProgressStrokesView inside the canvas host view.
- `.folio` files in the library folder are the source of truth. The Room index is a disposable cache.
- Grid invariant: every text line's baseline sits on a template grid line; every text line box is an integer multiple of the grid unit U. Fonts, inline LaTeX, tables, headings must not break it.
- Protected (hook-enforced): `.claude/settings.json`, `.claude/hooks/**`, `scripts/device/**`. Propose changes as a Blocked item with the exact diff.
- Never accept licenses, terms, or pairing prompts on the user's behalf.

## Session protocol
1. The STATUS.md snapshot is injected at session start. If missing, read `docs/plan/STATUS.md`.
2. All plan work uses the `next-task` skill. One task = one commit (large tasks: sub-commits `PNN-TNNa`, `b`).
3. Read only the current phase file (Grep `^### <task-id>`, then Read that block) and the anchors in the task's `Read:` line.
4. Done = every `Accept:` item verified by a command you ran in this session.
5. `device:` checks run through the `device-tester` subagent. `user:` checks go to STATUS `USER-CHECK` (non-blocking).
6. Needs the human to proceed → STATUS `Blocked`, then continue with the next unblocked task.
7. Spec deviation → `docs/plan/AMENDMENTS.md` entry + doc update in the same commit. Never deviate silently.
8. Phase end → REVIEW step (reviewer subagent, fixes, full `./gradlew qa`, tag `pNN-done`).
9. Interactive sessions: after an auto-compaction or 3 completed tasks, finish the current task, write the STATUS Handoff, stop. Fresh sessions beat long ones.

## Commands
| Purpose | Command |
|---|---|
| Environment check | `bash scripts/env/doctor.sh` |
| Build debug APK | `./gradlew :app:assembleDebug` |
| All checks | `./gradlew qa` (spotless, detekt, lint, unit + screenshot tests, verifyNoInternet) |
| Module tests | `./gradlew :core:text:testDebugUnitTest` (JVM modules: `./gradlew :core:model:test`) |
| Screenshot goldens | `./gradlew verifyRoborazziDebug` / `./gradlew recordRoborazziDebug` |
| Format | `./gradlew spotlessApply` |
| Device connect / facts | `bash scripts/device/connect.sh` / `bash scripts/device/info.sh` |
| Install + launch | `bash scripts/device/install.sh && bash scripts/device/launch.sh [--doc f] [--route r]` |
| Debug command | `bash scripts/device/debugcmd.sh <cmd> [arg]` (reply read from logcat tag FolioDebug) |
| Instrumented tests | `bash scripts/device/instrumented.sh :module [connectedDebugAndroidTest]` |
| Screenshot | `bash scripts/device/screenshot.sh <name>` -> `.device/screens/<name>.png` |
| Logs / frames / diag | `logcat.sh [n]`, `gfxinfo.sh [reset]`, `diag.sh layers`, `refresh`, `meminfo` (all in scripts/device) |
| Unattended loop | `bash scripts/auto/run-loop.sh [phase\|task]`, one fresh session per task; rerun to resume (the user starts it, never you) |

## Module map (details: docs/architecture/02-modules.md)
| Module | Owns |
|---|---|
| `app` | Application, MainActivity, navigation, DI graph, debug automation (debug source set) |
| `core:common` | dispatchers, Outcome, logging, PerfMonitor, FolioFs interface |
| `core:model` | JVM. Document, pages, objects, geometry, spatial index, commands, undo |
| `core:format` | JVM. `.folio` container, Wire protobuf, manifest JSON, migrations |
| `core:storage` | library folder, working copies, packer, Room index + FTS, trash, DataStore settings |
| `core:ink` | androidx.ink bridge: brushes, input routing, eraser, lasso, shape recognizer, stylus caps |
| `core:text` | markdown blocks, fonts, grid line-box layout, flows, math renderer |
| `core:render` | viewport, templates, tile caches, PageRenderer (screen, PNG, PDF) |
| `core:pdf` | PDF import, background rasterizer, export pipeline |
| `core:designsystem` | tokens, theme, fonts (UI), icons, shared components |
| `core:testing` | fakes, fixtures, rules |
| `feature:library` | home, folders, tags, search, bin |
| `feature:editor` | editor panes, canvas host, toolbar, tools, split view |
| `feature:settings` | settings screens |
| `tools:icongen` | JVM tool: Lucide SVG -> Compose ImageVector source |

## Where knowledge lives
| Need | Location |
|---|---|
| What next | `docs/plan/STATUS.md` -> current `docs/plan/phase-NN-*.md` |
| Requirements (R-IDs) | `docs/architecture/01-requirements.md` |
| Design (how) | `docs/architecture/NN-*.md`; decisions in `decisions.md` |
| Device facts | `docs/notes/device.md` |
| Toolchain, dependency list | `docs/notes/env.md`, `gradle/libs.versions.toml` |
| Performance numbers | `docs/notes/perf.md` (budgets: `12-performance.md`) |
| Longer gotchas | `docs/notes/gotchas.md` |
| Coding rules | `.claude/rules/*.md` (load automatically by path) |
| Visual direction | `docs/design/reference/*.png` + `11-design-system.md` |

## Definition of done (every task)
- All `Accept:` items verified; touched modules pass spotless, detekt, lint, unit tests. Full `./gradlew qa` at phase end.
- New logic has unit tests. New UI state has a Roborazzi screenshot test. Every bug fix starts with a failing test.
- No suppressed warning without a one-line reason. No TODO without an id listed under STATUS `Deferred`.
- Commit message: `<type>(<scope>): <summary> [PNN-TNN]`; body lists verification commands and results, updated goldens, amendments.
- STATUS.md updated in the same commit.

## Engineering defaults
- Kotlin, Jetpack Compose UI, Hilt, coroutines + Flow, Room, DataStore, Wire, kotlinx.serialization. Versions only in `gradle/libs.versions.toml`.
- Page space is PDF points (1/72 inch). Convert via Viewport helpers only. Name units: `xPt`, `sizePx`, `durationMs`.
- Hot paths (input, rendering, layout, typing): no per-event allocation, no logging, no Flow emission per point. Trace with PerfMonitor sections.
- Prefer boring, testable code. Pure logic in JVM modules. Android specifics behind small interfaces.
- Keep tool output small: Gradle with `--quiet --console=plain`, re-run only the failing task, pipe long output through `tail -n 80`. Never cat build logs or large files whole.

## Memory upkeep (hook-enforced limits)
- This file <= 200 lines. Each rule file <= 120 lines. STATUS.md <= 60 lines. Skill files <= 200 lines.
- `## Learned` holds at most 20 one-line entries, newest last. Add one when the same problem appears twice or a non-obvious fact would save a future session time. When full, or when an entry only matters for some paths, follow the `maintain-memory` skill.
- Auto memory is disabled for this project (`autoMemoryEnabled: false`) because work happens on two machines; everything worth keeping lives in the repository.

## Learned
<!-- Format: - YYYY-MM-DD <fact> (<task id>) -->
- 2026-09-27 SDK packages are installed by the USER (`cmdline-tools/latest/bin/android sdk install platforms/android-NN`); with cmdline-tools 23 installing is the license acceptance, see docs/notes/env.md (P00-T01)
- 2026-09-28 Test APKs (`instrumented.sh`) can be refused with INSTALL_FAILED_USER_RESTRICTED while the app APK installs (HyperOS "USB debugging (Security settings)" off); Gradle still says SUCCESS. Fallback: run the probe from a debug route in the app (docs/notes/device.md#quirks) (P01-S5)
- 2026-09-28 Downloads: only single `curl -fsSL https://raw.githubusercontent.com/...` commands pass the permission check (no loops, no unzip/python); read library sources on GitHub (docs/notes/gotchas.md) (P01-S3)
- 2026-09-28 Headless runs deny `sed -i`, `>` redirects, `{a,b}` brace paths, `xargs`/multi-file `cat` and `jar`; edit with Edit/Write, read with Read, keep Bash to one plain command (optionally `| grep`/`| tail`) (P02)
