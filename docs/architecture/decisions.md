# Architecture decisions
Compact ADRs. Status values: Proposed (needs spike evidence), Accepted, Superseded. Evidence lines are appended with dates by spikes and reviews (`Evidence YYYY-MM-DD:`). Date of initial decisions: 2026-09-26.

## ADR-001 UI stack
Status: Accepted
Context: Native Android, one tablet, modern animated UI, lowest pen latency.
Options: A Kotlin + Jetpack Compose UI with a View-based canvas host; B Compose-only canvas; C Flutter / cross-platform.
Decision: A. Compose for all chrome (library, toolbar, sheets, settings). The canvas is a custom `CanvasHostView` embedded with `AndroidView`, with androidx.ink's `InProgressStrokesView` on top.
Consequences: pen input bypasses recomposition; Compose remains the productive default elsewhere. Two UI toolkits meet at one well-defined seam (`CanvasController`).

## ADR-002 Ink stack
Status: Accepted (2026-09-27, P01-S1)
Context: Wet ink must be as low-latency as possible (R-INK-08); strokes need pressure/tilt brushes, geometry for erasing and lasso, and stable rendering.
Options: A androidx.ink 1.1.0-alpha (newest alpha; public brush customization API, fixes since 1.0.0); B androidx.ink 1.0.0 stable (Dec 2025); C custom renderer on androidx.graphics front-buffered surfaces.
Decision: A, pinned to an exact alpha. Front-buffered wet ink via `InProgressStrokesView`, finished strokes via `CanvasStrokeRenderer`, geometry for erase/lasso. The experimental mesh-editing partial eraser (1.1.0-alpha05+) is not used because its results are not yet serializable; Folio splits stroke inputs instead (06-ink-input.md#erasers). Our file format stores our own `StrokeInputs`, not ink's serialization, so a library change never breaks files.
Fallback: B if an alpha regresses on the device; C only if the spike proves ink unusable on HyperOS.
Revisit: when ink 1.1.0 goes stable (move to stable).
Evidence 2026-09-27 (P01-S1): Pad 7, debug build, ink 1.1.0-alpha09, route `spike-ink` (pressure pen, CanvasStrokeRenderer page, DisplayModeHelper), 50 synthetic `input stylus swipe` strokes per run (400 ms, 50 events each). Median of runs, worst in brackets.
| Handoff mode | Runs | `ink:onTouch` p50 / p95 / max ms | `ink:handoff` p50 / p95 ms | Janky frames | 3 screenshots after pen-up |
|---|---|---|---|---|---|
| commit (ViewTreeObserver frame-commit callback) | 3 | 0.20 / 0.28 (0.29) / 0.55 (0.63) | 6.4 / 8.8 (one 66 ms outlier) | 0.8% (1.6%) | stroke shown, all 3 |
| frame (Choreographer frame callback) | 1 | 0.20 / 0.28 / 0.49 | 7.8 / 8.2 | 0.4% | stroke shown, all 3 |
| immediate (inside `onStrokesFinished`) | 1 | 0.20 / 0.27 / 0.66 | 0.03 / 0.05 | 0.7% | stroke shown, all 3 |
Front buffer works on HyperOS: InProgressStrokesView adds a SurfaceView (BLAST, z=1) above the window on the first stroke. Requesting the 144 Hz mode left the display at 120 Hz (device.md#display). Screenshots settle >= 100 ms after pen-up, so single-frame flicker and felt latency are a USER-CHECK. Motion prediction was not wired in the spike (P03).
Decision 2026-09-27: A confirmed (p95 0.29 ms vs 1 ms budget, handoff clean). Handoff mode: commit, the only mode that removes wet strokes after the frame showing the committed stroke by construction (A-005).

## ADR-003 Committed content rendering
Status: Accepted (2026-09-27, P01-S2)
Context: Dense pages (1500+ strokes, text, images) must pan/zoom at 144 Hz.
Options: A software bitmap tiles (512 px) rendered off the main thread; B RenderNode tiles with compositing layers (GPU-cached display lists); C draw all objects every frame.
Decision (default): A, because the same code path serves PNG export and it is portable; B if it wins the spike clearly on jank. C rejected (cost grows with content).
Consequences: memory budget and tile invalidation logic required; zoom shows scaled tiles briefly until the new bucket renders.
Evidence 2026-09-27 (P01-S2b): Pad 7, route `spike-tiles`, A4 lined page, 1500 seeded strokes (pen/marker/highlighter), 512 px tiles, CanvasStrokeRenderer on a software canvas (A) or RenderNode recording with a compositing layer (B); 3 runs per strategy, median (worst). Display ran at 120 Hz (144 requested). Zoom 1 = bucket 5 (42 tiles incl. ring), zoom 3 = bucket 8.
| Metric | A bitmap tiles | B RenderNode tiles |
|---|---|---|
| zoom-anim 1->3->1 (800 ms each) janky % / p95 / p99 ms | 0.49 (0.50) / 15 (18) / 24 (28) | 0.49 (0.49) / 18 (18) / 22 (24) |
| 10 s finger pan at zoom 3, janky % / p95 / p99 ms | 0.06 (0.06) / 9 (9) / 10 (11) | 0.06 (0.06) / 9 (9) / 10 (11) |
| tile render p50 / p95 ms, bucket 5 | 3.72 / 8.02 (17.3) | 3.63 / 8.38 (9.29) (recording only; raster happens on the RenderThread) |
| tile render p50 / p95 ms, bucket 8 | 2.24 / 4.35 (5.19) | 1.96 / 4.00 (4.22) |
| settle (all visible tiles of a new bucket) p50 ms | 97 (106) | 80 (94) |
| `tiles:draw` p95 ms | 1.11 (1.39) | 0.82 (1.09) |
| meminfo PSS / Graphics / Native heap MiB | 426 / 148 / 202 (Native drops to 125 within seconds) | 272 / 126 / 71 |
| pixels differing from a direct software render (zoom 1 / 3) | 0.001% / 0.002% | 1.16% / 0.41% (GPU antialiasing differs) |
Both strategies stay far below 1% janky frames and are tied on jank; screenshots show no seams or missing tiles.
Decision 2026-09-27: A (tie rule; also pixel-identical to the export raster path). B's lower memory is noted; the tile cache budget (05-canvas-rendering.md#tiles, 25% of largeMemoryClass) must bound A's bitmaps (P03).

## ADR-004 Library storage
Status: Accepted (2026-09-28, P01-S6)
Context: User-visible folder for cross-platform copying (R-FILE-01); fast scanning and random-access ZIP reads; personal sideloaded app.
Options: A `MANAGE_EXTERNAL_STORAGE` + `java.io.File` in `Documents/Folio`; B Storage Access Framework tree URI + DocumentFile; C app-specific external storage (`Android/data`), hidden from most file managers.
Decision: A. Debug builds use `Documents/Folio-Debug` so tests never touch real notes.
Consequences: one-time permission screen; not Play-Store compatible (irrelevant for sideloading). B stays the migration path if a future Android version restricts A.
Evidence 2026-09-28 (P01-S6): Pad 7, route `spike-io`, All-files access granted, java.io on `/sdcard/Documents/Folio-Debug` (FUSE). Working copy in app files: 4 x 10 MB random assets (STORED) + 10 x 1 MB pages (DEFLATED) -> 46.0 MB `.folio`.
| Measurement | Result |
|---|---|
| Pack (CRC + ZIP write + fsync + rename + directory fsync), 4 runs | 1391 / 1410 / 1413 / 1450 ms (write 1282..1363, fsync 35..39, rename 13..57, CRC 33..40); directory fsync supported |
| Read-back verify (all entries, CRC checked) | ok, 16 entries, mimetype first |
| App killed (`stop.sh`) mid-pack, 3 runs | target intact and valid every time; tmp removed by start-up cleanup (1 run) or already gone after the kill (2 runs, FUSE dropped the half-written file) |
| FileObserver on `Folio-Debug/fixtures` for `push-fixture.sh` (external writer through FUSE) | CREATE + CLOSE_WRITE (DELETE first when replacing) delivered, under about 150 ms after the push (bound limited by the 6 s `debugcmd.sh` round trip) |
| FileObserver during our own pack | CREATE `.tmp`, CLOSE_WRITE, MOVED_FROM `.tmp`, MOVED_TO target |
Decision 2026-09-28: A confirmed. Pack stays within 1.5 s for 46 MB with little margin (P11 budget watch: most time is the FUSE write).

## ADR-005 File format
Status: Accepted
Context: One file per document, containing everything incl. images visible to others (R-FILE-02), compact ink, future desktop reader (R-FILE-04).
Options: A ZIP container + JSON manifest + protobuf pages + Markdown flows + raw assets; B single SQLite file per document; C one big protobuf; D JSON only.
Decision: A (04-file-format.md). Wire generates Kotlin from `.proto`; the schema doubles as documentation for other readers. Markdown flows are directly readable.
Consequences: whole-file repack on save (mitigated by working copy + STORED assets); schema discipline needed (reserved fields, migrations, golden files).

## ADR-006 PDF export
Status: Accepted (2026-09-28, P01-S5)
Context: Annotated PDF export must keep original PDF pages as vectors; Android's `PdfDocument` cannot import existing pages. PdfBox-Android (last release 2.0.27.0, January 2023) is stale and its BouncyCastle dependency carries known CVEs. Android API level 36.1 added `android.graphics.pdf.component` (stamp annotations with path/image/text objects, `PdfRenderer.write`).
Options: A PdfDocument for Folio pages + PdfBox-Android `LayerUtility` overlay merge for PDF-backed pages (BouncyCastle excluded if possible); B platform stamp annotations (only if the device API level provides them); C raster original page at 200 dpi + vector annotations.
Decision (default): A, behind `PdfExporter` strategies with B and C as fallbacks.
Consequences: one stale third-party library, used only at export time on local files the user created; revisit if a maintained alternative appears.
Evidence 2026-09-28 (P01-S5): Pad 7, `PdfSpikeProbe` run in the debug app (route `spike-pdf`; the separate test APK was refused that day). Source: generated 100-page A4 PDF (PdfDocument: text, rules, one 64 px image per page), 178 KB. Export: 10 overlay pages drawn with PdfDocument (ink curves, label, red mark) merged by `PdfBoxOverlayMerger` (PdfBox-Android 2.0.27.0 `LayerUtility.importPageAsForm` + append content stream), BouncyCastle excluded.
| Measurement | Run 1 / 2 / 3 |
|---|---|
| First page (open + 0.5x preview) ms | 267 / 26 / 25 |
| 512 px center tile per page p50 / p95 ms, zoom 1 | 2.5 / 2.6 (all runs) |
| same, zoom 2 | 2.0 / 2.1 (all runs) |
| Export (overlays + merge + save, 100 pages) ms | 241 / 108 / 80 (overlays 10..11) |
| Output | 202 KB (+13%), 100 pages, all A4, overlay mark red on annotated page, absent on a plain page |
Host test (`PdfBoxOverlayMergerTest`, Robolectric) also shows the merge adds one form XObject only to target pages and works without BouncyCastle. The generated pages are simpler than real scans or papers, so real render times will be higher.
Decision 2026-09-28: A confirmed (merge correct, export 0.24 s vs 10 s budget); BouncyCastle stays excluded (no encrypted PDFs; import of encrypted files is a P08 question).

## ADR-007 LaTeX rendering
Status: Accepted (2026-09-27, P01-S4): C jlatexmath-android
Context: Inline and block LaTeX with exact metrics (ascent/depth) so inline math never breaks the grid (R-TXT-04); offline; vector output for PDF export.
Options: A RaTeX (MIT; Rust core with KaTeX-compatible parsing and layout, >99.5% KaTeX syntax coverage claimed, Android binding drawing on Canvas, depth metrics); B zly2006/latex (Kotlin Multiplatform Compose renderer with pre-measure API); C jlatexmath-android (GPL-2 with classpath exception, older); D KaTeX in an offscreen WebView (rejected: slow, async, WebView dependency).
Decision (default): A, via the `MathRenderer` interface so B or C can replace it.
Consequences: native `.so` libraries in the APK (arm64 only is enough for the Pad 7; include arm64-v8a only).
Evidence 2026-09-27 (P01-S4a, host part): `MathRenderer` + adapters in core:text, corpus testdata/math/corpus.txt (40 formulas), inline style at 50 px (body M at the text reference scale). Host numbers from `MathProbeTest` (Robolectric native graphics); A loads an Android-only `.so`, so its numbers come from `MathProbeInstrumentedTest` on the tablet (P01-S4b). APK delta: clean debug builds with and without the library.
| Option | License | Parsed | Layout+draw p50 / p95 ms | Ink outside box | Vector output | APK delta |
|---|---|---|---|---|---|---|
| A RaTeX 0.1.14 | MIT | device (S4b) | device (S4b) | device (S4b) | draws Canvas text, rects and paths only (source); PDF check S4b | +3.54 MB arm64-v8a only (+9.35 MB with 3 ABIs) |
| B huarangmeng latex 1.5.0 (zly2006/latex) | MIT | not built | - | - | no: measuring and export need a Composable scope, export is PNG/SVG only, no Canvas/DrawScope API (README) | - |
| C jlatexmath-android 0.2.0 | GPL-2.0 + linking exception | 39/40 (97.5%, `\ce` unknown) | 0.54 / 1.87 (host) | max 1.85 px, 6 of 39 over 1 px (with `setTrueValues`; the default box adds 0.18 em padding) | Graphics2D over Canvas; PDF check S4b | +0.69 MB |
B fails the vector rule by API, so it was not built. The app now packages arm64-v8a only (also drops about 4.7 MB of other libraries' x86_64/armeabi-v7a natives).
Evidence 2026-09-27 (P01-S4b, device): `MathProbeInstrumentedTest` on the Pad 7 (passed), 5 warm-up formulas, then 40 formulas x 5 repeats, inline style at 50 px.
| Option | Parsed | Layout+draw p50 / p95 ms | Max ink outside box px (formulas > 1 px) | PDF output |
|---|---|---|---|---|
| A RaTeX | 40/40 (100%) | 1.49 / 5.09 | 1.56 (4) | vector only (76 KB) |
| C jlatexmath | 39/40 (97.5%, `\ce` unknown) | 0.36 / 0.80 | 1.85 (6) | vector only (49 KB) |
Decision 2026-09-27: C. Rule: success >= 95%, p95 <= 4 ms and vector output; only C meets all three (A misses p95 by 1.1 ms). Both overshoot the box on large operators and accents by up to 2 px at 50 px, so the adapter pads ascent and depth by 0.04 em (A-007). Risks accepted: the library is archived (last release 2020) and GPL-2.0 with linking exception (allowed by .claude/rules/gradle.md with this note); no mhchem `\ce`. A stays the fallback behind `MathRenderer`.

## ADR-008 Text layout engine
Status: Accepted (2026-09-27, P01-S3)
Context: Every line must snap to template lines regardless of font (R-TXT-03); long text must stay fast (R-TXT-05); Notewise's slow text-box workflow must not repeat (R-CORE-01).
Options: A Compose text per block with fixed line boxes + per-font baseline correction + cap-height normalization, block-based editing with a single live field; B one BasicTextField per flow (simple, but slow for long text and hard to snap); C fully custom text layout + IME (maximum control, very high cost).
Decision (default): A. Spike fallback: per-line placement from TextLayoutResult metrics if baseline error exceeds 0.5 px.
Consequences: cross-block selection needs custom handling; the focused block shows Markdown markers dimmed (no hidden characters).
Evidence 2026-09-27 (P01-S3a, host): `BaselineProbeTest` on Robolectric native graphics (host minikin/Skia), 20 bundled fonts x 3 paragraphs (6 to 10 lines), college U = 7.1 mm, body M (cap 0.45 U), text width 175 mm. px = Pad 7 screen px at zoom x fit-width (5.15 px/pt at zoom 1).
| Baseline method | Max baseline error px, zoom 1 / 2 / 4 (all fonts) | Cap-height error px | Layout ms per paragraph (host median) |
|---|---|---|---|
| FONT_DESCENT (ref 4 px/pt, shift = font descent) | 0.64 to 10.35 / 1.28 to 20.7 / 2.56 to 41.4 (fails for all 20) | 0.000 | 0.8 to 4.4 |
| GRID_PITCH (ref round(4U)/U px/pt, shift from first layout baseline) | 0.000 / 0.000 / 0.000 | 0.000 | 0.7 to 1.2 |
Cause of the drift: `LineHeightStyle` rounds line heights up to whole px and layout baselines are whole px; with 4 px/pt, U = 80.5 px becomes 81 px per line. Golden: core/text/src/test/screenshots/FontSpecimen_compact.png. Device run (`BaselineProbeInstrumentedTest`, tag FolioProbe) is P01-S3b.
Evidence 2026-09-27 (P01-S3b, device): `BaselineProbeInstrumentedTest` on the Pad 7 (1/1 passed), same fonts and paragraphs: GRID_PITCH 0.000 px at zoom 1, 2 and 4 for all 20 fonts; FONT_DESCENT 0.64 to 10.35 px at zoom 1 (worst: Shadows Into Light, IBM Plex Mono 2.91, Architects Daughter 2.52); cap-height error 0.000 px; layout 0.29 to 0.58 ms per paragraph.
Decision 2026-09-27: A with the GRID_PITCH placement (A-006), a form of the planned TextLayoutResult fallback.

## ADR-009 Index and search
Status: Accepted
Context: Library views, tags, backlinks, full-text search over typed text (R-ORG-03) with files as the source of truth.
Decision: Room database with FTS4, rebuilt from files at any time; manifests carry everything the index needs (no page decoding during scans).
Consequences: index staleness handled by incremental scans and FileObserver; never store user data only in the index.

## ADR-010 Undo model
Status: Accepted
Context: 50 undo steps (R-FILE-05), split view with independent panes, gestures as single steps.
Decision: command pattern with inverses computed at execution; per-pane UndoManager (capacity 50, coalescing window 1000 ms); session-only (not persisted).
Consequences: every mutation must be a command; property tests guard inverse correctness.

## ADR-011 Offline and bundled assets
Status: Accepted
Context: Strictly offline (R-DEV-02) yet Google Fonts, icons, and math fonts are needed.
Decision: no INTERNET permission (removed from merged manifests, verified by `verifyNoInternet`); fonts (OFL), Lucide icons (ISC), and math fonts are bundled; web links open in the user's browser via intents.
Consequences: APK size grows (~15-25 MB); acceptable for a single-device app.

## ADR-012 Build and modules
Status: Accepted
Context: Q51 unanswered; the build is done by an AI agent that benefits from enforced boundaries and fast, focused test runs.
Options: A multi-module with convention plugins (02-modules.md); B single module with packages.
Decision: A. JVM modules for model and format keep most tests fast and Android-free. minSdk = min(35, device SDK) (set in P00-T02); compileSdk/targetSdk = latest stable.
Consequences: more build configuration up front (P00), cheaper and safer changes afterwards.
Note 2026-09-27 (P00-T02): device reports SDK 36 (Android 16, HyperOS OS3.0), so minSdk = 35.
Note 2026-09-27 (P00-T03, A-001): detekt 2.0.0-alpha.6 allowed (build-time only) because compose-rules 0.6.7 requires it and detekt 1.23.8 targets Kotlin 2.0.21; move to stable 2.0.0 when released.

## ADR-013 Navigation
Status: Accepted
Decision: Navigation 3 if a stable release exists at P00-T03, otherwise navigation-compose. Note 2026-09-27 (P00-T03): navigation3 1.2.0 is stable (dl.google.com maven-metadata), so Navigation 3 is used. Few routes (onboarding, library, editor, settings); editor split view is internal state, not navigation.

## ADR-014 Icons
Status: Accepted
Decision: Lucide outlined icons (ISC), converted at build time by `tools:icongen` into Compose ImageVectors. Visually close to the reference's thin outlined style; no icon fonts, no runtime SVG parsing, no material-icons-extended.
