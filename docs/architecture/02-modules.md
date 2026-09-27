# 02 Modules

## Module list
| Module | Type | Responsibility |
|---|---|---|
| `app` | Android application | Application class, MainActivity, navigation host, Hilt graph, debug automation (debug source set) |
| `core:common` | JVM (A-002) | FolioDispatchers, Outcome, FolioLog, Clock, PerfMonitor, FolioFs interface |
| `core:model` | JVM | Ids, units, geometry, spatial index, Document/Page/Object types, EditCommands, UndoManager |
| `core:format` | JVM | `.folio` container read/write, Wire protobuf codecs, manifest JSON, migrations |
| `core:storage` | Android library | LibraryConfig, permission, FolioFs impl, working copies, packer, recovery, Room index + FTS, repositories, DocumentSession, DataStore settings, bin purge worker |
| `core:ink` | Android library | BrushCatalog, InputRouter, StylusCapabilities, stroke conversion, erasers, lasso membership, shape recognizer, ruler math |
| `core:text` | Android library | Markdown block parser, FontRegistry, FontMetricsCache, BlockLayout, FlowLayout, MathRenderer adapters |
| `core:render` | Android library | Viewport, PageStackLayout, TemplateRenderer, tile caches, BitmapPool, PageRenderer, DisplayModeHelper |
| `core:pdf` | Android library | PdfRasterizer, PDF import, PdfExporter strategies, PNG export |
| `core:designsystem` | Android library | Tokens, FolioTheme, UI fonts, FolioIcons (generated), shared components |
| `core:testing` | JVM (A-002) | Fakes, rules, builders, fixtures helpers (testImplementation only) |
| `feature:library` | Android library | Onboarding, library home, folders, tags, search, bin, new-note entry |
| `feature:editor` | Android library | EditorRoute/ViewModel, EditorSession wiring, CanvasHostView, toolbar, tools, text editing overlay, page panel, split view, export sheet |
| `feature:settings` | Android library | Settings screens |
| `tools:icongen` | JVM application | Lucide SVG -> ImageVector Kotlin source generator |
| `benchmark` | com.android.test | Macrobenchmarks + baseline profile (added in P11) |

Allowed dependencies are in `.claude/rules/architecture-boundaries.md`. Graph:
```
app -> feature:library, feature:editor, feature:settings, core:*
feature:* -> core:*
core:pdf -> core:render, core:format, core:model, core:common
core:render -> core:ink, core:text, core:model, core:common
core:storage -> core:format, core:model, core:common
core:ink, core:text -> core:model, core:common
core:format -> core:model -> core:common
core:designsystem -> core:common
core:testing -> core:model, core:common   (A-002; consumed via testImplementation only)
```
Enforced by the root task `verifyModuleGraph` (part of `qa`).

## Build logic
Included build `build-logic` with convention plugins:
- `folio.android.application`, `folio.android.library`: SDK levels, Java 17 bytecode (no toolchain, A-002), Kotlin options, R8 settings, test options. AGP 9 built-in Kotlin (no kotlin-android plugin).
- `folio.jvm.library`: Kotlin JVM + JUnit.
- `folio.compose`: Compose compiler plugin, BOM, tooling, stability config.
- `folio.hilt`: Hilt + KSP. `folio.room`: Room + KSP + schema export to `core/storage/schemas/`.
- `folio.wire`: Wire Gradle plugin, Kotlin output. `folio.screenshot`: Robolectric + Roborazzi.
- `folio.quality` (applied by every module convention): spotless, detekt, lint config, a per-module `qa`. `folio.android.application` registers `verifyNoInternet<Variant>` (app and androidTest merged manifests). The root build aggregates: root `qa` = every module `qa` + root spotless + `verifyModuleGraph` + `verifyNoInternet` (A-004).
SDK: compileSdk/targetSdk = latest stable; minSdk from the device (P00-T02, never below 35 unless the device is older).

## Dependencies
Pin exact versions in `gradle/libs.versions.toml` (P00-T03). Stable unless marked.
| Area | Libraries |
|---|---|
| Build | Android Gradle Plugin, Kotlin, KSP, Gradle wrapper |
| UI | Compose BOM (ui, foundation, material3, animation, ui-tooling), activity-compose, lifecycle (runtime-compose, viewmodel-compose, process), core-ktx, window / material3-adaptive (window size classes), Navigation 3 if a stable release exists, else navigation-compose |
| DI | Hilt (android, compiler), hilt-lifecycle-viewmodel-compose (A-001), hilt-work |
| Async | kotlinx-coroutines (android, test) |
| Data | kotlinx-serialization-json, kotlinx-collections-immutable, Room (runtime, ktx, compiler), DataStore Preferences, WorkManager |
| Ink | androidx.ink `ink-authoring`, `ink-brush`, `ink-geometry`, `ink-rendering`, `ink-strokes` (+ `-compose` interop artifacts only if used) at the newest **1.1.0-alpha** (ADR-002); androidx.graphics-core and androidx.input motion prediction arrive transitively |
| Tracing | androidx.tracing-ktx |
| Protobuf | Wire runtime + Gradle plugin |
| Markdown | org.commonmark `commonmark`, `commonmark-ext-gfm-tables`, `commonmark-ext-gfm-strikethrough`, `commonmark-ext-task-list-items` |
| Math | jlatexmath-android `ru.noties:jlatexmath-android` (ADR-007, P01-S4; A-007) |
| PDF | PdfBox-Android `com.tom-roush:pdfbox-android` (ADR-006), BouncyCastle excluded (P01-S5) |
| Images | Coil 3 core + compose without any network artifact (library thumbnails only), or plain ImageDecoder if Coil adds nothing |
| Tests | JUnit4, Truth, Turbine, Robolectric, Roborazzi (+ compose, junit rule), androidx.test (runner, rules, ext-junit), compose ui-test-junit4, uiautomator, hilt-android-testing |
| Perf | benchmark-macro-junit4, profileinstaller, baselineprofile Gradle plugin (P11) |
| Quality | spotless + ktlint, detekt (2.0 alpha, A-001) + Compose rules plugin |

Not allowed: networking libraries, analytics, crash reporters, Gson, RxJava, WebView-based renderers.

## Editor state
- `EditorSession` (one per pane) wraps a `DocumentSession` from core:storage and adds: active tool + tool options, selection, focused text block, viewport state, pane id.
- `EditorViewModel` holds one or two sessions (split view) and exposes `EditorUiState` for chrome (toolbar, sheets, save state). Chrome never observes per-point state.
- `CanvasController` (interface implemented by EditorSession) is the only API the canvas host sees: `commit(strokes)`, `hitTest`, `currentTool()`, `requestRender(bounds)`, `document`, `viewport`.
- Document mutations: only `session.execute(command)`. Undo/redo: `session.undo()/redo()`. Each pane has its own UndoManager.

## Packages
`dev.folio.<module path>` e.g. `dev.folio.core.render.tiles`, `dev.folio.feature.editor.canvas`. Debug-only code: `dev.folio.app.debug`, spikes: `dev.folio.app.spikes`.
