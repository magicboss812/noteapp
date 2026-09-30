# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P03
next: P03-T10
updated: 2026-09-30

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.

## Current phase progress
- P03: T01, T02, T03, T04, T05, T06, T07, T08, T09

## Blocked (needs user; stops dependent tasks)
- P02-T06: storage onboarding never seen on the tablet (Roborazzi + unit tests cover it). Recheck 2026-09-29: All files access was still granted before any grant, so the `[Checked]` mark did not revoke it. -> turn off Settings > Apps > Folio Debug > Permissions > All files access, then write "-> revoked" here (Claude reruns without grant-storage.sh first); or approve new protected `scripts/device/revoke-storage.sh`: `source "$(dirname "$0")/_common.sh"; init_device; dshell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE default; echo "MANAGE_EXTERNAL_STORAGE: $(dshell appops get --uid "$PKG" MANAGE_EXTERNAL_STORAGE | head -n1)"`. Blocks nothing else. [technically revoked, no app permissions yet to change (none at all), so there is no "All files access" permission]

## USER-CHECK (human verification; non-blocking)
- P03-T07: install the debug app, `open lined:4` (or any doc) on the canvas route and write a page of notes quickly with the Focus Pen -> no flicker, gap or double (darker) line when lifting the pen; reopen after closing the app -> everything is still there, no "(conflict ...)" copies in Documents/Folio-Debug. [Checked, smooth writing with no flickering, template and strokes sections only flicker or not render while moving/zooming the page, should be minized so page already prerenders during zoom or at least stays longer even behind screen (doesn't happen when app is restarted, so when strokes load from the folie file, but generally this issue needs to be minized); brush strokes and everything are saved on app restart]
- P03-T08: after drawing, run `bash scripts/device/debugcmd.sh tool eraser` (or `tool eraser,partial`) and erase with the Focus Pen -> ink vanishes while you move, nothing flickers back when you lift; `debugcmd.sh undo` brings the whole gesture back; `tool pen` to draw again.[Checked, erasing very smooth and responsive, undo also works, hovering above does not erase things fortunately; eraser width should be resizeable, but ignore this request if it is already considered]
- P03-T09: on the canvas route hold the Focus Pen just above the page -> a thin ring follows the tip (pen: about the stroke width; after `debugcmd.sh tool eraser`: the 10 pt eraser circle) and disappears when the pen touches or leaves; the pencil still shades wider and lighter when tilted. Pen button: HyperOS keeps it (P01-S7), so holding it must NOT erase; if it ever does erase, note it here. [no thin ring visible]

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).
- D-002 P01-T08: spike route spike-fonts + core:text spike/ (remove in P06-T03; spike-ink/spike-stylus went in P03-T09); USER-CHECKs answered (A-008).
- D-004 P01-REVIEW: `GridParagraph.draw` allocates a Compose canvas wrapper per call; cache it or mark HOT PATH before P06-T05 paints text into tiles.
- D-005 P01-REVIEW: .claude/rules/text-engine.md line 3 should name the grid-pitch reference scale + measured first-baseline correction (A-006); the edit was denied in the headless run, apply it interactively.
- D-006 P01-S3 (user feedback): decide BACKLOG B-12 (free font size tied to the rules, top/middle/bottom placement in the line box) in P06-T02 before building size styles.
- D-007 P02-REVIEW: repositories do not refuse open documents (favorite/rename/move/delete of an open doc); add an open-documents check with a typed error in P05-T01.
- D-009 P02-REVIEW: unpack fsyncs every entry and bounds total size at 8 GB; one fsync pass and a size-ratio zip-bomb bound (P11 perf).
- D-010 P02-REVIEW: session packs drop unknown manifest keys, unknown proto fields and unknown object kinds (04#versioning); preserve raw JSON and opaque objects before v2 exists. Also missing: LibraryWatcher event test (P05).
- D-011 P03-T03: CUSTOM templates render through `TemplateAssets` but nothing implements it yet (PNG decode from session assets, PDF page raster via core:pdf) and there is no import UI; add both in P08 (with the PDF raster).
- D-012 P03-T06: wet ink has no motion prediction yet (06#wet-ink, ADR-002); `androidx.input:input-motionprediction` is pre-release (1.0.0-rc01 on androidx main), so decide it with a decisions.md note and measure in P03-T10.
- D-013 P03-T08: erase worker time has no PerfMonitor section (only `ink:onTouch` covers eraser input); add `ink:erase` per drained batch and measure it in P03-T10.
- D-014 P03-T09: tilt shading has no off switch (A-025): dry rendering applies tilt whenever stored inputs carry tilt; the P11-T01 setting needs a stored per-stroke choice (or dropping tilt at commit) so wet and dry ink agree.

## Handoff (<= 5 lines, overwritten each session)
- P03-T09 done (A-025): core:ink `StylusFeatures`/`StylusPreferences`/`HoverTarget`; editor `HoverCursor` + `HoverRingView` (plain View above the overlay slot), button eraser in `StylusTools`, `CanvasController.stylusPreferences`; spike-ink/spike-stylus code deleted, app no longer depends on androidx.ink directly.
- Debug `state` reports `canvas.stylus` with `button=` and `hoverRing=`; synthetic `input stylus` sends no hover, so the ring is only checkable by the user.
- `ink:commit` p95 was 3.09 ms in the T08 run (budget 2 ms, 1.92 in T07): fix or record in P03-T10. The first swipe after `open` is sometimes dropped by `input stylus`; resend it.
- Next: P03-T10 performance pass (also D-012 motion prediction decision, D-013 `ink:erase` section).
