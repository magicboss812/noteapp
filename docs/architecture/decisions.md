# Architecture decisions
Compact ADRs. Status values: Proposed (needs spike evidence), Accepted, Superseded. Evidence lines are appended with dates by spikes and reviews (`Evidence YYYY-MM-DD:`). Date of initial decisions: 2026-09-26.

## ADR-001 UI stack
Status: Accepted
Context: Native Android, one tablet, modern animated UI, lowest pen latency.
Options: A Kotlin + Jetpack Compose UI with a View-based canvas host; B Compose-only canvas; C Flutter / cross-platform.
Decision: A. Compose for all chrome (library, toolbar, sheets, settings). The canvas is a custom `CanvasHostView` embedded with `AndroidView`, with androidx.ink's `InProgressStrokesView` on top.
Consequences: pen input bypasses recomposition; Compose remains the productive default elsewhere. Two UI toolkits meet at one well-defined seam (`CanvasController`).

## ADR-002 Ink stack
Status: Proposed (baseline accepted; P01-S1 adds evidence)
Context: Wet ink must be as low-latency as possible (R-INK-08); strokes need pressure/tilt brushes, geometry for erasing and lasso, and stable rendering.
Options: A androidx.ink 1.1.0-alpha (newest alpha; public brush customization API, fixes since 1.0.0); B androidx.ink 1.0.0 stable (Dec 2025); C custom renderer on androidx.graphics front-buffered surfaces.
Decision: A, pinned to an exact alpha. Front-buffered wet ink via `InProgressStrokesView`, finished strokes via `CanvasStrokeRenderer`, geometry for erase/lasso. The experimental mesh-editing partial eraser (1.1.0-alpha05+) is not used because its results are not yet serializable; Folio splits stroke inputs instead (06-ink-input.md#erasers). Our file format stores our own `StrokeInputs`, not ink's serialization, so a library change never breaks files.
Fallback: B if an alpha regresses on the device; C only if the spike proves ink unusable on HyperOS.
Revisit: when ink 1.1.0 goes stable (move to stable).

## ADR-003 Committed content rendering
Status: Proposed (decided by P01-S2)
Context: Dense pages (1500+ strokes, text, images) must pan/zoom at 144 Hz.
Options: A software bitmap tiles (512 px) rendered off the main thread; B RenderNode tiles with compositing layers (GPU-cached display lists); C draw all objects every frame.
Decision (default): A, because the same code path serves PNG export and it is portable; B if it wins the spike clearly on jank. C rejected (cost grows with content).
Consequences: memory budget and tile invalidation logic required; zoom shows scaled tiles briefly until the new bucket renders.

## ADR-004 Library storage
Status: Proposed (baseline accepted; P01-S6 adds evidence)
Context: User-visible folder for cross-platform copying (R-FILE-01); fast scanning and random-access ZIP reads; personal sideloaded app.
Options: A `MANAGE_EXTERNAL_STORAGE` + `java.io.File` in `Documents/Folio`; B Storage Access Framework tree URI + DocumentFile; C app-specific external storage (`Android/data`), hidden from most file managers.
Decision: A. Debug builds use `Documents/Folio-Debug` so tests never touch real notes.
Consequences: one-time permission screen; not Play-Store compatible (irrelevant for sideloading). B stays the migration path if a future Android version restricts A.

## ADR-005 File format
Status: Accepted
Context: One file per document, containing everything incl. images visible to others (R-FILE-02), compact ink, future desktop reader (R-FILE-04).
Options: A ZIP container + JSON manifest + protobuf pages + Markdown flows + raw assets; B single SQLite file per document; C one big protobuf; D JSON only.
Decision: A (04-file-format.md). Wire generates Kotlin from `.proto`; the schema doubles as documentation for other readers. Markdown flows are directly readable.
Consequences: whole-file repack on save (mitigated by working copy + STORED assets); schema discipline needed (reserved fields, migrations, golden files).

## ADR-006 PDF export
Status: Proposed (decided by P01-S5)
Context: Annotated PDF export must keep original PDF pages as vectors; Android's `PdfDocument` cannot import existing pages. PdfBox-Android (last release 2.0.27.0, January 2023) is stale and its BouncyCastle dependency carries known CVEs. Android API level 36.1 added `android.graphics.pdf.component` (stamp annotations with path/image/text objects, `PdfRenderer.write`).
Options: A PdfDocument for Folio pages + PdfBox-Android `LayerUtility` overlay merge for PDF-backed pages (BouncyCastle excluded if possible); B platform stamp annotations (only if the device API level provides them); C raster original page at 200 dpi + vector annotations.
Decision (default): A, behind `PdfExporter` strategies with B and C as fallbacks.
Consequences: one stale third-party library, used only at export time on local files the user created; revisit if a maintained alternative appears.

## ADR-007 LaTeX rendering
Status: Proposed (decided by P01-S4)
Context: Inline and block LaTeX with exact metrics (ascent/depth) so inline math never breaks the grid (R-TXT-04); offline; vector output for PDF export.
Options: A RaTeX (MIT; Rust core with KaTeX-compatible parsing and layout, >99.5% KaTeX syntax coverage claimed, Android binding drawing on Canvas, depth metrics); B zly2006/latex (Kotlin Multiplatform Compose renderer with pre-measure API); C jlatexmath-android (GPL-2 with classpath exception, older); D KaTeX in an offscreen WebView (rejected: slow, async, WebView dependency).
Decision (default): A, via the `MathRenderer` interface so B or C can replace it.
Consequences: native `.so` libraries in the APK (arm64 only is enough for the Pad 7; include arm64-v8a only).

## ADR-008 Text layout engine
Status: Proposed (decided by P01-S3)
Context: Every line must snap to template lines regardless of font (R-TXT-03); long text must stay fast (R-TXT-05); Notewise's slow text-box workflow must not repeat (R-CORE-01).
Options: A Compose text per block with fixed line boxes + per-font baseline correction + cap-height normalization, block-based editing with a single live field; B one BasicTextField per flow (simple, but slow for long text and hard to snap); C fully custom text layout + IME (maximum control, very high cost).
Decision (default): A. Spike fallback: per-line placement from TextLayoutResult metrics if baseline error exceeds 0.5 px.
Consequences: cross-block selection needs custom handling; the focused block shows Markdown markers dimmed (no hidden characters).

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
