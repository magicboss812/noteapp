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

## A-007 2026-09-27 P01-S4: LaTeX via jlatexmath-android instead of RaTeX
- Change: ADR-007 picks option C (jlatexmath-android 0.2.0) instead of the default A (RaTeX); the RaTeX dependency is removed by P01-T08. `MathBox` gets 0.04 em padding on every side (P01-T08: `\vec` arrows also overshoot sideways).
- Reason: device probe, 40 formulas: RaTeX 100% parsed but layout+draw p95 5.09 ms (budget 4 ms); jlatexmath 97.5% parsed (no `\ce`), p95 0.80 ms, vector PDF output. Both draw up to 1.6 / 1.9 px outside their reported box at 50 px.
- Impact: 07-text-engine.md#math, decisions.md ADR-007, docs/notes/env.md; P06-T07 (math adapter, padding, no mhchem support in the corpus tests).

## A-008 2026-09-28 P01-T08: spike results carried into later phases
- Change: promoted and kept: `FontRegistry` + 20 bundled fonts, `FontMetricsCache`, `GridTextLayouter` (core:text), `MathRenderer` + `JLatexMathRenderer` (core:text math), `PdfBoxOverlayMerger` (core:pdf); app packages arm64-v8a only. Deleted: tile, IO and PDF probe code, RaTeX. Kept temporarily (Deferred D-002): the debug routes `spike-ink`, `spike-fonts`, `spike-stylus` with their probe code (app spikes/, core:text spike/) because open USER-CHECKs (P01-S1, S3, S7) need them on the tablet.
- Reason: phase-01 exit says spike code is deleted or promoted; three screens are still the only way to answer the USER-CHECKs. The removal duty was added to the P03-T09 and P06-T03 task blocks (P01 REVIEW).
- Impact: P03 (tiles use bitmap strategy A, ADR-003; `StylusCapabilities` defaults from device.md#stylus; P03-T09 removes `spike-ink` and `spike-stylus` after the USER-CHECKs), P02 packer (write protocol measured, ADR-004), P06-T02 (FontRegistry and FontMetricsCache exist), P06-T03 (seed: GridTextLayouter, BaselineProbe tests become the regression suite, then `spike-fonts` goes), P06-T07 (JLatexMathRenderer exists), P09 export (merger exists).

## A-009 2026-09-28 P02-T01: loaded page bodies live in Document.pageBodies
- Change: `Document` gets `pageBodies: PersistentMap<PageId, Page>` (decoded bodies held by the session); `PageRef` carries `contentBounds`. Object commands require the body of their page to be loaded; the session loads bodies before executing and evicts only clean, unreferenced ones (LRU 30). Geometry adds `RectPt.distanceTo`, `offset`, `Affine.after`; rect edges are inclusive and `RectPt.EMPTY` is the union identity. Detekt `MagicNumber.ignoreEnums = true`.
- Reason: 03-document-model.md had `EditCommand.execute(doc)` but no place for decoded page bodies in `Document`; keeping them in the immutable state keeps commands pure and undo inverses exact without a second mutable page store.
- Impact: 03-document-model.md#document; P02-T02 commands, P02-T09 session (page cache = `pageBodies` + LRU bookkeeping).

## A-010 2026-09-28 P02-T02: exact-inverse helper commands, UpdateFlows/UpdateAssets, requiredPages
- Change: besides the listed commands, core:model has `InsertObjectsAt`, `UpdateObjects`, `SetObjectOrder`, `RestorePages`, `SetPageOrder` (used as exact inverses), `UpdateFlows(put, remove)` (flow create/delete/style; not in the spec list) and `UpdateAssets(put, remove)` instead of `AddAsset`. `EditFlow` carries text range edits. Commands expose `requiredPages` and `coalesceKey`. `UndoManager` takes the injected `Clock` (monotonic time for the 1000 ms window) and exposes `nextUndo`/`nextRedo`. core:testing depends on core:model (`ModelFixtures`), allowed by architecture-boundaries.
- Reason: inverse commands computed from float math (inverse affine, index arithmetic) would not restore the document exactly; the 200-command property test demands equality. Text boxes and sticky notes need flow creation and deletion as undoable steps.
- Impact: 03-document-model.md#commands-and-undo; P06 (block edits map to `TextEdit` ranges), P07 (lasso, recolor), P02-T09 (session loads `requiredPages` before execute/undo/redo).

## A-011 2026-09-28 P02-T04: manifest page summaries carry the full background
- Change: manifest pages and `defaults` get the additive keys `background` and `origin` (infinite pages); container code is `FolioEntries` (names, order, STORED rule), `EntryReader` (implemented by `FolioContainerReader` over ZipFile and later by the working copy), `DocumentCodec` (read) and `DocumentEntries` (write). Migrations rewrite the manifest JSON (`Migration.migrateManifest`); page-payload migrations get added with the first v2 change.
- Reason: `PageRef` holds spec + full background (03-document-model.md#document) and must be built from the manifest alone; the documented summary had only the template kind.
- Impact: 04-file-format.md#manifest; P02-T05 (working copy implements `EntryReader`), P02-T07 scanner (reads manifest only).

## A-012 2026-09-28 P02-T05: streaming FolioFs, backup of the opened version, JavaFileFolioFs in core:common
- Change: `FolioFs` gains `stat`, `openRead`, `writeAtomic(path) { out -> }` (atomic streaming write incl. directory fsync) and `deleteRecursively`; the java.io implementation `JavaFileFolioFs` lives in core:common (P02-T06 listed it in core:storage) and backs `TempDirFolioFs`. The packer copies the source to `files/backup/<docId>.folio` once per working copy, before its first replacement, instead of "after the rename". Unpacking streams the ZIP (`ZipInputStream`) through `FolioFs`, 8 GB total bound. Detekt: `TooManyFunctions.ignoreOverridden`, `ReturnCount.excludeGuardClauses`.
- Reason: packs of 50 MB documents cannot go through in-memory `readBytes`/`writeBytesAtomic`; after the rename the previous version no longer exists, so the backup has to be taken before, and once per editing session keeps it cheap (one extra copy) and more useful (state before the session).
- Impact: 04-file-format.md#write-protocol; P02-T06 (uses `JavaFileFolioFs` for the library root), P02-T09 session (flush autosaver, then pack, under one mutex).

## A-013 2026-09-28 P02-T07: index keyed by path; FolioFs.localFile
- Change: `documents` has primary key `path` (docId indexed), `tags`/`links` reference the document path with cascading deletes, `doc_fts` stores the path (unicode61 tokenizer for umlauts). Moves are a delete plus insert of the same docId. `FolioFs.localFile(path)` exposes the java.io.File for ZipFile random access. The scanner also deletes hidden `*.folio*.tmp` files older than 10 minutes (crash recovery). Build: the Robolectric JDK 21 `--add-opens` flags moved from the screenshot plugin to every Android module's unit tests.
- Reason: 09-storage-library.md keeps both rows for duplicated docIds, which a docId primary key cannot hold; the scan budget needs random access (manifest + search text only) rather than streaming whole ZIPs.
- Impact: 09-storage-library.md#index; P02-T08 repositories (resolve by docId via `documentsById`, rank title hits first in Kotlin), P05 library UI.

## A-014 2026-09-28 P02-T08: repository details
- Change: repositories address documents by library path; metadata edits of closed documents rewrite the container with a JSON-level manifest patch (`ManifestPatch`); duplicates take the new file name as title; `LibraryRepository` also owns folder create/rename/move/tint/delete; bin state is a `StateFlow` read from `.trash` sidecars. `Clock` and `ManifestApp` are provided by :app's AppModule.
- Reason: the spec leaves title/ids of duplicates and the handling of unknown manifest keys open; a JSON patch is the only way to keep keys a newer version wrote.
- Impact: 09-storage-library.md#repositories; P05 library UI, P02-T09 session (`open`).

## A-015 2026-09-28 P02-T09: session registry, recovery trigger, modifiedMs
- Change: `DocumentSessions` opens sessions by library path (not `DocumentRepository.open(docId)`) and packs them on app stop; :app registers the ProcessLifecycleOwner observer with a `dagger.Lazy` so nothing is built at start. Recovery runs from `LibraryEntryViewModel` the first time access is granted (`Recovery.runOnce`), not in `Application.onCreate`. `meta.modifiedMs` is stamped into the manifest at write time from the last edit instead of being changed by commands.
- Reason: 12-performance.md keeps `Application.onCreate` free of storage work, and recovery needs All-files access; stamping modifiedMs inside commands would break exact undo.
- Impact: 03-document-model.md#sessions; P04 editor (EditorSession wraps a DocumentSession), P05 (RecoveryEvents snackbar).

## A-016 2026-09-29 P02-REVIEW: storage safety fixes
- Change: ids from files are validated (`FolioIds`) and `FolioFs` rejects empty/`.` segments; a manual duplicate (second file with a docId whose working copy belongs to another existing file) gets a new docId on open, a dirty copy follows its moved file; the dirty mark is written to `base.json` before the entries; orphaned working copies are kept 7 days from the first time recovery reported them (`orphanSinceMs`); pages stay pinned by edit generation until the autosave of that generation is written; `LibraryScanner` switches to io itself; undo/redo pop only after the inverse succeeded; `DocumentSessions.close` packs under NonCancellable and each session backs up the file as opened; rename rewrites in place then moves; file stems are capped at 200 UTF-8 bytes (conflict names included); onboarding names the configured folder.
- Reason: reviewer findings (P02 REVIEW): data loss or deletion of other documents' working copies, main-thread IO, crashes on hostile files.
- Impact: 04-file-format.md#versioning (id rules, known unknown-key loss after session edits, D-010), 04#crash-recovery, 09-storage-library.md#index; Deferred D-007..D-010.

## A-017 2026-09-29 P03-T01: viewport details
- Change: the zoom bucket is the smallest 2^(k/2) at or above the scale (`ceil`, not `round`); stack gap and side padding are 16/24 dp at fit-width and zoom with the pages (stack space in pt); the half-screen overscroll is vertical only, horizontally the column is centered while narrower than the view and clamped to its edges otherwise; view offsets are doubles; a canvas page fits its origin width at fit-width.
- Reason: the doc's `round` formula contradicted "bucket at or above"; dp constants in screen space would make page tops depend on zoom; float offsets deep in a 200-page stack at 8x lose 0.25 px, over the 0.5 px focal budget.
- Impact: 05-canvas-rendering.md#viewport; P03-T02 canvas host and T04 tiles use `Viewport`, `PageStackLayout`, `ZoomBuckets` (core/render viewport package).

## A-018 2026-09-30 P03-T03: template details
- Change: `GridUnit` lives in core:model instead of core/render `template/`, sanitizes decoded values (U 2..50 mm, NaN/<= 0 -> kind default, margins 0..A3 height), and only LINED/GRID/DOTTED read `spacingPt`. GRAPH_AXES axes sit on the grid lines nearest the page center (not the exact center). Structured kinds (Cornell, music, planners, custom) repeat their origin frame on infinite pages. CUSTOM images come through a `TemplateAssets` interface. Until P03-T04, `BackgroundTileLayer` draws templates directly each frame (device: dotted 20 pages jank 0.25%, p95 19 ms).
- Reason: core:text needs GridUnit but cannot depend on core:render. Axes off the grid would break the grid invariant for text. Asset decoding (PDF raster in core:pdf) cannot live in core:render.
- Impact: 02-modules.md (core:model), 05-canvas-rendering.md#templates, 07-text-engine.md#grid-unit; D-008 now covers only rects and custom page sizes; D-011.

## A-019 2026-09-30 P03-T04: decoded value ranges
- Change: page decoding rejects NaN/infinite/|v| > 10^6 pt coordinates (rects, points, stroke samples), custom page edges outside (0, 14400] pt (page entries and manifest), widths outside (0, 500] pt and non-finite rotations or pressure gamma; inverted rect edges decode as the empty rect.
- Reason: STATUS D-008; tiles, the page stack and the ink mesher must never see NaN or overflowing tile indices from a hostile file.
- Impact: 04-file-format.md#protobuf-schema (decoding paragraph); closes D-008.

## A-020 2026-09-30 P03-T04: tile and page renderer details
- Change: `RenderTarget` has SCREEN_BACKGROUND and SCREEN_CONTENT instead of one SCREEN target with layer flags; `PageRenderer.draw` takes the `PageRef` plus a `PageContent` snapshot (null = no objects). Empty tiles are cached without bitmaps; the budget is split evenly between the two layers and is soft for pinned (on-screen) tiles; prefetch only while the budget has room; missing current-bucket slots fall back to the nearest other bucket. core:ink gets `StrokeBuilder` (stock androidx.ink families until BrushCatalog, P03-T05) and exposes ink-brush/ink-strokes as `api`.
- Reason: a background tile of a blank page is pure paper and a content tile without objects is transparent, so rendering them wastes memory; at 3200x2136 one layer needs up to ~48 visible tiles, so a hard budget would evict tiles on screen.
- Impact: 05-canvas-rendering.md#tiles, #page-renderer.

## A-021 2026-09-30 P03-T05: brush catalog details
- Change: the pressure curve is baked into each family as a piecewise-linear response (15 samples of `p^gamma`), with gamma snapped to steps of 0.05; families are cached per (kind, version, gamma, tilt). Stored specs with an unknown version render with the nearest known version (0 -> oldest, newer -> latest). Highlighter opacity is a paint color function, so the stored color stays opaque. Pencil grain is a procedural 64 px texture (`BrushTextures`, id versioned) that every renderer must receive. Pencil tilt maps 20..69 deg to width 100..250% and opacity 100..55%. `BrushCatalog.specOf(brush)` recovers the spec of a finished wet stroke. Screenshot goldens render real meshes on the JVM (ink-nativeloader-jvm) but without per-vertex opacity.
- Reason: wet ink gets raw MotionEvents, so the curve cannot be applied to inputs; baking it into the family keeps wet and dry identical and stored pressure raw. Snapping bounds the cache (31 curves). Robolectric draws meshes as paths.
- Impact: 06-ink-input.md#brushes.

## A-022 2026-09-30 P03-T07: conflict check by content, recovery before the first open
- Change: `base.json` records `sourceCrc32` (CRC-32 of the bytes the copy last packed). A source with the recorded size but another mtime counts as unchanged when its CRC-32 matches; open then records the mtime it reports now. `DocumentSessions` runs start-up recovery (`Recovery.runOnce`, now blocking concurrent callers until it finished) before every open.
- Reason: on the Pad 7 shared storage a `.folio` replaced by rename reports its new mtime right after the write and its original mtime later, so every pack after a relaunch became a conflict copy and later strokes went into the copy. Separately, the library screen's recovery could pack a killed process's dirty copy while a session already held that copy, which also produced a conflict copy (device run, P03-T07).
- Impact: 04-file-format.md#write-protocol, #conflicts; 03-document-model.md#sessions.

## A-023 2026-09-30 P03-T07: dry handoff re-renders tiles
- Change: a committed stroke is not painted incrementally into existing tiles; the new document marks the tiles under its bounds stale and they re-render on the render dispatcher like any edit. The wet copy stays until every current-bucket content tile under the stroke's on-screen part is fresh (`TileLayer.isDrawn`), then the layers are invalidated and `removeFinishedStrokes` runs from the frame commit callback registered right after. A rejected `AddObjects` drops the wet stroke. The page is fixed at stroke start; stroke ids are new random `ObjectId`s; stored inputs come from the finished stroke's input batch (`StrokeBuilder.inputsOf`). `CanvasController.commitStrokes` runs the command off the main thread. `InProgressStrokesView.eagerInit()` runs once the wet layer is attached.
- Reason: step 4 of the handoff already keeps the wet stroke until its tile is drawn, so a full tile re-render (p95 4..8 ms on 1500-stroke pages, ADR-003) only delays the removal, never shows a gap; incremental paint would need a second paint path for highlighter alpha and z-order that the next re-render replaces anyway. Running the command on main cost 3.7 ms p50 (budget 2 ms p95). With lazy init, 2 of 3 cold starts never created the front-buffer surface, so strokes never finished.
- Impact: 05-canvas-rendering.md#dry-handoff.

## A-024 2026-09-30 P03-T08: eraser command, preview and hit test
- Change: an erase gesture commits as a `Batch` of one `ReplaceObjects` per touched stroke instead of a single `ReplaceObjects`. The live preview is the page body with the gesture's result applied (identical object instances to the command's), handed to the content tiles, instead of a temporary tile mask. The stroke eraser tests the padded segment rectangle as two `ImmutableTriangle`s, not `ImmutableParallelogram.fromSegmentAndPadding`. The partial eraser also cuts a centerline segment it crosses between two kept inputs. `CanvasController` gains `execute(command)`, `activeTool` (`CanvasTool` PEN/ERASER) and `eraserOptions`; "clear page" moves to the P04 toolbar and shape outlines to P07 (shapes do not exist yet).
- Reason: a single `ReplaceObjects` puts all fragments at the lowest removed z-index, which reorders strokes that were not adjacent (the spec requires z position kept). A preview body needs no second paint path and makes the commit invisible (the tiles do not change again). ink 1.1.0-alpha09 reports false hits for rotated parallelograms (docs/notes/gotchas.md). Without the cut, a fast eraser crossing a sparsely sampled segment would leave the stroke whole.
- Impact: 06-ink-input.md#erasers; 02-modules.md#editor-state.

## A-025 2026-09-30 P03-T09: hover ring as a View, stylus gating, no tilt switch yet
- Change: the hover cursor ring is a plain View (`HoverRingView`) between the Compose overlay slot and the wet-ink layer, fed by a `HoverTarget` on `InputRouter`, not part of the Compose overlay. Gating lives in `StylusFeatures` (core:ink) with `StylusPreferences` (hover ring, `PenButtonAction`) read through `CanvasController.stylusPreferences`; the button eraser is decided at stylus down like every routing decision. Settings rows come from `StylusFeatures.visibleSettings`. Tilt shading has no off switch yet (Deferred D-014).
- Reason: hover arrives at about 457 Hz; Compose state per hover event would recompose per motion event, which CLAUDE.md forbids for pen input. Committed strokes apply tilt behaviors whenever stored inputs carry tilt, so an off switch that only changed wet ink would make wet and dry ink disagree.
- Impact: 05-canvas-rendering.md#layers; 06-ink-input.md#stylus-capabilities; 10-editor-ui.md settings (P11-T01 reads `visibleSettings`).

## A-026 2026-09-30 P03-T10: tiles during gestures, base tiles, two known budget gaps
- Change: tiles are no longer requested only after 100 ms idle. A pan at the last requested bucket also requests newly uncovered tiles (throttled to 50 ms). Each layer keeps protected base tiles (fit-width bucket minus 4) of the visible pages, which the budget never evicts. The missing-tile fallback layers buckets nearest first, with clip-out, instead of drawing one nearest bucket. Prefetch counts only the current bucket's tiles of shown pages plus base tiles against the budget. `ink:erase` is traced per drained batch on the eraser worker.
- Known gaps: (1) R-PERF-04 "p95 frame <= 7 ms at 144 Hz" is not met: the panel runs 120 Hz. gfxinfo percentiles measure from the intended vsync, so they include queueing (p50 12, p95 21 ms), while janky frames are 0.24% and no deadline miss comes from the UI thread. Janky % stays the gfxinfo gate, and P11 measures CPU frame time with Macrobenchmark `FrameTimingMetric` against 7 ms. (2) `ink:commit` p95 is 2.02 ms against 2 ms in a debug build (p50 0.96). It is re-measured in the P11 benchmark build, and moving input conversion off main is the fix if it still misses (D-015).
- Reason: user check P03-T07 reported the template and strokes flickering or missing while moving and zooming, but not right after a restart. Pans outran the one-tile ring. Zoom-out showed never-rendered areas. One partial fallback bucket left holes. A cache full of old-bucket tiles stopped prefetch entirely. Measured after the fix: no blank tiles in a mid-pan screenshot at zoom 2, `render:settle` p95 119 ms.
- Impact: 05-canvas-rendering.md#tiles; 12-performance.md#budgets (R-PERF-04 row); .claude/rules/rendering.md gesture line (edit denied in the headless run, D-016).

## A-027 2026-09-30 P04-T01: Lucide 1.x names, four extra color tokens, fixed Fraunces opsz
- Change: the icon set uses Lucide 1.49.0 names `house` (was home) and `trash` (was trash-2, same drawing) and adds `plus` and `menu`. Color tokens gain onAccent, accentInverse (snackbar action), pageBorder (the page border value from #elevation) and scrim (the sheet scrim values from #elevation). Fraunces pins opsz to each style's font size instead of "auto". core:designsystem ships `inter_var.ttf` under the same resource name as core:text.
- Reason: `home.svg` and `trash-2.svg` no longer exist in Lucide 1.x. The snackbar sits on textPrimary, where accent has too little contrast, and the other values were already in the doc but had no token. Compose applies no automatic optical sizing to variable fonts. A shared resource name lets the APK merge keep one 876 KB copy while each module stays self-contained.
- Impact: 11-design-system.md#colors, #typography, #icons; docs/notes/env.md Dependencies.

## A-028 2026-10-01 P04-T02: debug canvas route replaced by the editor, library placeholder lists documents
- Change: the debug `canvas` route and its stopgap controller are gone. `open` makes sure the document exists, then navigates to the editor route; `state` reports route `editor`, and `route library` pops the navigator back (the editor ViewModel is cleared and the document packed). `DebugHooks` exposes an `EditorCanvasListener` and receives the `AppNavigator` in `attach`. Until the P05 library exists, the library placeholder lists every indexed document (title, folder, page count; tap opens it).
- Reason: the debug commands must drive the same EditorSession the user sees, and "navigation from the library placeholder" needs something to tap.
- Impact: docs/notes/device.md quirks; P05-T01 replaces the placeholder list.

## A-029 2026-10-01 P04-T04: toolbar grip, floating pill carries the options row, vertical segmented tabs
- Change: every dock mode is moved with a grip on row 1 (the spec only named the floating grip). A docked toolbar being dragged dims while a preview pill follows the finger. The floating pill shows grip + tools and keeps the tool options row below it (the doc pill and Home are hidden; system back leaves). Collapsed, the pill keeps its grip next to the current tool. Handedness is read as "which side side-docking means" and moves side-docked placements on change; the toggle itself waits for the settings screen (D-025). There is no bottom dock. `SegmentedTabs` (core:designsystem) gains a `vertical` layout for the eraser modes on the options rail.
- Reason: without a grip on the docked rows there is no way to undock; the options row is needed to change pen width or color while floating; a vertical rail cannot hold horizontal tabs.
- Impact: 10-editor-ui.md#toolbar-docking.

## A-030 2026-10-01 P04-T10: Notewise look for the design system and editor chrome
- Change: tokens, type, shapes and components follow the measured `docs/design/notewise/DESIGN.md`. Colors are renamed and extended to its roles (canvasSurround -> canvas, accentSoft -> accentContainer/accentContainerStrong, plus library gradient, surfaceToolbar, iconToolbar, closeChip, accentTrack and others); light values marked "(~)" there are Folio picks. Literata replaces Fraunces for display and card titles; Lucide icons are emitted with stroke 2. Toolbar pills are 44 dp stadiums with a 1 dp border (shadow in light theme only), cells are 36 dp circles inside 44 dp targets, row 1 grows from 52 to 60 dp, color dots shrink to 22 dp with a 4 dp selection dot, eraser sizes use a ring. Popovers get a centered title and a close chip; dialogs use text buttons, and a destructive confirm is danger text without a fill (resolves D-023). The library placeholder uses the library gradient and FolioTheme type. Sticky notes and the TOP/LEFT/RIGHT/FLOAT docks stay (Folio features Notewise lacks).
- Reason: the user chose Notewise as the visual reference after T01..T04; matching it now is cheaper than after the library and text screens exist.
- Impact: 11-design-system.md (rewritten), 10-editor-ui.md#toolbar and #tool-options, 07-text-engine.md (Literata), 12-performance.md (font sizes), docs/notes/env.md (fonts).

