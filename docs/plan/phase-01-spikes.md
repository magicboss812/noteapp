# P01 Spikes (risk first)
Goal: replace the riskiest assumptions with measurements on the Pad 7 before building on them. Use the `spike` skill.
Exit: ADR-002..ADR-008 in decisions.md show `Status: Accepted` with dated evidence, or a recorded default with reason; spike code deleted or promoted.
Spikes are independent. If the tablet is unavailable, do the JVM-measurable parts first.

### P01-S1 Wet ink latency and dry handoff on HyperOS
Implements: R-INK-08
Read: 06-ink-input.md#wet-ink, 05-canvas-rendering.md#dry-handoff, decisions.md#adr-002-ink-stack
Question: Does androidx.ink front-buffered wet ink work on HyperOS at 144 Hz, and is our dry handoff flicker-free?
Build: debug route `spike-ink`: white page view drawing committed strokes with CanvasStrokeRenderer, InProgressStrokesView on top, pressure pen brush, DisplayModeHelper requesting the highest refresh mode.
Measure:
- `diag.sh layers` while drawing (front-buffer layer present?), `diag.sh refresh` (active mode).
- PerfMonitor `ink:onTouch` main-thread p50/p95 over 50 `input.sh stylus-swipe` strokes.
- Handoff: 3 screenshots right after a stroke ends; all must show the stroke.
Decision rule: handoff clean and `ink:onTouch` p95 <= 1 ms -> Accepted. No front buffer on HyperOS -> still Accepted (library falls back), note it in device.md.
Output: ADR-002 evidence; user: "Write on the spike-ink screen and in Xiaomi Notes. Rate perceived lag 1-5 and any flicker when lifting the pen."

### P01-S2 Committed-content renderer strategy
Implements: R-INK-08, R-PERF-04
Read: 05-canvas-rendering.md#tiles, decisions.md#adr-003-committed-content-rendering
Question: Bitmap tiles (A) or RenderNode tiles (B) for committed content?
Build: debug route `spike-tiles`: A4 page, lined template, 1500 seeded synthetic strokes (mixed brushes and sizes). A: 512 px ARGB_8888 tiles rendered on the render dispatcher with a software Canvas (CanvasStrokeRenderer if it supports software canvases, else stroke outline Paths). B: one RenderNode per tile with a compositing layer.
Measure (both): tile render time p50/p95 at zoom 1 and 3; `debugcmd.sh zoom-anim 1.0,3.0,800` and a scripted 10 s pan (finger swipes) with `gfxinfo.sh` jank % and p95 frame time; `diag.sh meminfo`; visual equality against a Roborazzi golden of the same page.
Decision rule: lowest jank with <= 1% janky frames; tie -> A (simpler, shared with export).
Output: ADR-003 evidence and decision.

### P01-S3 Grid-snapped text metrics across fonts
Implements: R-TXT-03, R-TXT-08
Read: 07-text-engine.md#line-box, 07-text-engine.md#font-normalization, 07-text-engine.md#fonts
Question: Does a fixed line box plus per-font baseline correction put every baseline on the rule for all bundled fonts at all zoom levels?
Build: fetch the 20 fonts listed in 07-text-engine.md#fonts (OFL, from github.com/google/fonts) into `core/text/src/main/res/font/` with licenses in `core/text/src/main/assets/licenses/`. Instrumented test in `:core:text` laying out 3 paragraphs per font on a lined template (U = 7.1 mm) at zoom 1, 2, 4. Debug route `spike-fonts` shows all fonts on ruled paper.
Measure: max |baseline - ruleY| in px per font and zoom; cap-height error vs target; layout time per paragraph.
Decision rule: max error <= 0.5 px everywhere -> ADR-008 Accepted. Otherwise switch to per-line placement from TextLayoutResult metrics and re-measure.
Output: ADR-008 evidence; user: "Open spike-fonts. Do all fonts sit on the lines and look equally sized?"

### P01-S4 Math renderer
Implements: R-TXT-02, R-TXT-04
Read: 07-text-engine.md#math, decisions.md#adr-007-latex-rendering
Question: Which native LaTeX renderer gives KaTeX-level coverage, exact metrics, speed, and vector output?
Options: A RaTeX Android binding (MIT, KaTeX-compatible Rust core, JNI Canvas rendering with depth metrics). B zly2006/latex (KMP Compose, pre-measure API). C ru.noties jlatexmath-android (GPL-2 with classpath exception).
Build: `MathRenderer` interface in core:text: `layout(latex, fontSizePx, color): MathBox(widthPx, ascentPx, depthPx)` + `draw(canvas, x, baselineY)`. One adapter per option. Corpus `testdata/math/corpus.txt` (40 formulas: fractions, roots, sums, integrals, limits, matrices, cases, aligned, Greek, accents, vectors, units, chemistry).
Measure: parse success rate; layout+draw p50/p95; metric accuracy (drawn ink bounds vs ascent/depth within 1 px); draws into a PdfDocument canvas as vectors (no bitmaps); APK size delta; license.
Decision rule: success >= 95%, p95 <= 4 ms, vector output -> best such option; tie -> A.
Output: ADR-007 evidence and decision.

### P01-S5 PDF rendering and export merge
Implements: R-MED-01, R-FILE-03
Read: 08-pdf.md, decisions.md#adr-006-pdf-export
Question: Can we render 100-page PDFs fast and export annotated PDFs that keep the original vector pages?
Build: test code generates a 100-page vector PDF (PdfDocument: text, lines, a small image). PdfRasterizer prototype renders pages/tiles. Export prototype: overlay page drawn with PdfDocument (sample ink paths and text) merged onto original pages via PdfBox-Android `LayerUtility` (form XObject overlay), BouncyCastle excluded if unencrypted PDFs work without it.
Measure: first-page render ms; per-page raster p95 at 2 zoom levels; export time for 100 pages with overlays on 10; output size vs input; reopen output with PdfRenderer: page count, page sizes, pixel check at overlay location.
Decision rule: merge correct and export <= 10 s -> Accepted. Else fallback order: platform `android.graphics.pdf.component` StampAnnotation path objects (only if device API level has it) -> raster 200 dpi pages + vector overlay.
Output: ADR-006 evidence and decision.

### P01-S6 Library storage IO
Implements: R-FILE-01
Read: 09-storage-library.md#permission, 04-file-format.md#write-protocol, decisions.md#adr-004-library-storage
Question: Is All-files access with java.io.File fast and atomic enough on HyperOS FUSE storage?
Build: debug route `spike-io`: check permission, pack a synthetic 50 MB `.folio` (STORED 40 MB image assets + deflated protobuf) to `Folio-Debug/spike/`, fsync, rename; list entries back; FileObserver on the folder.
Measure: pack time; crash safety (start pack, `stop.sh` mid-way, relaunch: target never corrupt, temp removed); FileObserver latency for `push-fixture.sh` events.
Decision rule: pack <= 1.5 s and atomic -> ADR-004 Accepted. Else SAF-free alternative analysis in ADR.
Output: ADR-004 evidence.

### P01-S7 Stylus capability probe
Implements: R-INK-02, R-INK-06
Read: 06-ink-input.md#stylus-capabilities
Question: Which Focus Pen signals reach a third-party app: pressure range, tilt, orientation, hover, buttons, sample rate?
Build: debug route `spike-stylus`: live readout of InputDevice motion ranges, tool type, pressure, tilt, orientation, distance, buttonState, hover events, historical sample count per frame; writes `Folio-Debug/probe/stylus.json` continuously.
Measure: automated part with `input.sh stylus-swipe` (synthetic events lack tilt; note it). Physical part: user: "Open spike-stylus. Hover, write, tilt the pen, press each Focus Pen button while hovering and while touching. Then append '-> ok'." Afterwards `pull.sh probe` and analyze.
Output: docs/notes/device.md#stylus (tilt yes/no + range, hover yes/no, which buttons arrive, sample rate) and `StylusCapabilities` defaults. P03-T09 depends on this USER-CHECK.

### P01-T08 Consolidate spike results
Read: decisions.md
Do:
1. Every ADR-002..008: status + dated evidence table.
2. Update chosen-option details in 05, 06, 07, 08, 09 architecture docs.
3. Delete spike-only code; promote reusable parts into target modules with tests.
4. AMENDMENTS entries for plan impact (list affected task ids).
Accept:
- [ ] each ADR-002..008 is Accepted or has a recorded default with reason
- [ ] `./gradlew qa` green after spike cleanup
Verify: `./gradlew qa`
