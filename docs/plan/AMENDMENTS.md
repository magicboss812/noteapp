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

