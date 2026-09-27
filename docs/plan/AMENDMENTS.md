# Amendments
Every deviation from docs/architecture or docs/plan is recorded here before or with the change. Newest last.

Format:
## A-NNN YYYY-MM-DD <task-id>: <title>
- Change: what is different from the spec
- Reason: evidence (numbers, errors, API facts)
- Impact: affected docs sections and task ids (updated in the same commit)

## A-001 2026-09-27 P00-T03: detekt 2.0 alpha and hilt-lifecycle-viewmodel-compose
- Change: detekt pinned to 2.0.0-alpha.6 (plugin id `dev.detekt`) instead of a stable release; `androidx.hilt:hilt-lifecycle-viewmodel-compose` replaces `hilt-navigation-compose`.
- Reason: the latest compose-rules (io.nlopez.compose.rules:detekt 0.6.7) depends on dev.detekt:detekt-core 2.0.0-alpha.6 (its POM); the only stable detekt (1.23.8) is built on Kotlin 2.0.21 while the project uses Kotlin 2.4.20 and AGP 9. With Navigation 3 (stable 1.2.0, ADR-013) `hiltViewModel()` lives in hilt-lifecycle-viewmodel-compose; hilt-navigation-compose targets navigation-compose. detekt is build-time only, nothing ships.
- Impact: 02-modules.md#dependencies (DI and Quality rows), decisions.md ADR-012 note, .claude/rules/gradle.md alpha list. Revisit when detekt 2.0.0 is stable.

## A-002 2026-09-27 P00-T04: core:common and core:testing are JVM modules; bytecode 17 without a toolchain
- Change: `core:common` and `core:testing` are Kotlin/JVM modules (spec: Android libraries). `core:testing -> core:model, core:common` is an allowed edge (test-only consumer). No Gradle JVM toolchain: JDK 21 runs the build, bytecode target is 17 via compileOptions / jvmTarget / `--release 17`. build-tools pinned to the installed 37.0.0; compileSdk 37 minor 2.
- Reason: `core:model` and `core:format` are JVM modules and must depend on `core:common` and use `core:testing` in tests; a JVM module cannot depend on an Android library. Both modules only hold pure Kotlin APIs (Android sinks for FolioLog/PerfMonitor tracing live in :app). A toolchain of 17 needs a JDK 17 install or network auto-provisioning; only JDK 21 is installed (env.md). AGP 9.4.1 defaults to build-tools 36.0.0, which is not installed and would trigger a license-bearing download.
- Impact: 02-modules.md#module-list and graph; root `verifyModuleGraph` encodes the graph.

## A-003 2026-09-27 P00-T06: PerfMonitor traces through a TraceSink; Roborazzi plugin via build-logic
- Change: `PerfMonitor` (JVM core:common) emits trace sections through a `TraceSink` interface; :app installs a sink backed by platform `android.os.Trace` instead of androidx.tracing. Release keeps only the trace calls (`PerfMonitor.enabled = BuildConfig.DEBUG`). The Roborazzi Gradle plugin is loaded from build-logic's classpath (Maven Central artifact) instead of the `plugins {}` block.
- Reason: androidx.tracing 2.0.3 reworked its API; the platform Trace (minSdk 35) produces the same Perfetto/atrace sections without an extra dependency and keeps core:common Android-free (A-002). Roborazzi publishes its plugin marker only to the Gradle Plugin Portal, which the build does not use (repositories: google, mavenCentral).
- Impact: 12-performance.md#measurement; tracing-ktx stays in the catalog unused until a module needs it.

## A-004 2026-09-27 P00-REVIEW: qa task layout; test-only EPL-1.0 allowed
- Change: `qa` is layered: each module gets `qa` from `folio.quality`, `verifyNoInternet<Variant>` tasks come from `folio.android.application` (including the androidTest APK manifest), and the root `qa`/`verifyNoInternet` aggregate them (spec: both live in the root build). The gradle rule's license list allows EPL-1.0 for test-only artifacts.
- Reason: per-module tasks keep module builds independent (configuration cache, project isolation later); the root task depends on every module `qa` explicitly (reviewer finding). JUnit4 (EPL-1.0) is required by the testing rule and 02-modules.md#dependencies but never ships in an APK; `verifyModuleGraph` keeps core:testing out of non-test configurations.
- Impact: 02-modules.md#build-logic, .claude/rules/gradle.md.

## A-005 2026-09-27 P01-S1: dry handoff waits for the frame-commit callback
- Change: step 3 of the dry handoff removes finished wet strokes in a `ViewTreeObserver.registerFrameCommitCallback` callback instead of a `Choreographer` frame callback. `DisplayModeHelper` (core:render) exists from P01-S1; P03 wires it into the canvas host.
- Reason: a frame callback posted after `invalidate()` runs in the animation phase of the same vsync, before that frame's draw, so it removes wet ink before the committed layer has drawn it (breaks "never remove earlier"). The commit callback fires after the frame is submitted: `ink:handoff` p50 6.4 ms (about one 120 Hz frame), all post-stroke screenshots clean (decisions.md ADR-002 evidence).
- Impact: 05-canvas-rendering.md#dry-handoff; P03 canvas host tasks (dry handoff, DisplayModeHelper).

## A-006 2026-09-27 P01-S3: grid-pitch reference scale and measured baseline correction
- Change: the line-box layout uses a reference scale r = round(4*U)/U px per pt (one grid unit = whole px) instead of a fixed 4 px per pt, and shifts each block by `k*U*r - firstLineBaseline` from the layout result instead of the font descent from `FontMetricsCache`. Bundled fonts: 37 files (variable where Google publishes them), about 28 MB.
- Reason: Robolectric native-graphics probe, 20 fonts x 3 paragraphs, U = 7.1 mm: the descent shift missed rules by 0.64 to 10.35 px at zoom 1 (2.6 to 41 px at zoom 4) because `LineHeightStyle` rounds each line height up to whole px (80.5 -> 81 px: 0.125 pt drift per line) and layout baselines are whole px. The grid-pitch variant put every baseline on its rule (0.000 px at zoom 1, 2, 4). Confirmed on the Pad 7 (P01-S3b): identical numbers.
- Impact: 07-text-engine.md#line-box, decisions.md ADR-008; P06-T02 (`FontMetricsCache` no longer feeds placement), P06-T03 (`BlockLayout` uses the grid-pitch scale per U).

