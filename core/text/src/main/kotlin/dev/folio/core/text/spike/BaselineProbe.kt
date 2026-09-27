package dev.folio.core.text.spike

import dev.folio.core.text.fonts.BundledFontFamily
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BaselineMethod
import dev.folio.core.text.layout.GridTextLayouter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Baseline and cap-height accuracy of one family laid out with one [BaselineMethod] (P01-S3). */
class BaselineProbeResult(
    val family: String,
    val method: BaselineMethod,
    val lines: Int,
    /** Largest |baseline - rule| over all lines, in pt. */
    val maxBaselineErrorPt: Float,
    /** |laid out cap height - target| at the reference size, in pt. */
    val capErrorPt: Float,
    val fontSizePt: Float,
    val capRatio: Float,
    /** Median layout time per paragraph. */
    val layoutMedianMs: Double,
) {
    /** Baseline error in screen px on the Pad 7 at [zoom] x fit-width. */
    fun maxBaselineErrorPx(zoom: Float): Float = maxBaselineErrorPt * zoom * BaselineProbe.PAD7_FIT_SCALE

    /** Cap-height error in screen px on the Pad 7 at fit-width. */
    val capErrorPx: Float get() = capErrorPt * BaselineProbe.PAD7_FIT_SCALE
}

/**
 * Spike P01-S3: lays out three paragraphs per font on a college-ruled grid (U = 7.1 mm, body M)
 * and measures how far each baseline lands from its rule. Shared by the Robolectric test, the
 * instrumented test and the `spike-fonts` debug route. Deleted or promoted by P01-T08.
 */
object BaselineProbe {
    /** College ruling, 7.1 mm (05-canvas-rendering.md#templates). */
    const val UNIT_PT = 7.1f * 72f / 25.4f

    /** Body M target cap height as a fraction of U (07-text-engine.md#font-normalization). */
    const val BODY_M_CAP = 0.45f

    /** Text column: A4 width minus the 25 mm margin line and 10 mm on the right. */
    const val WIDTH_PT = (210f - 25f - 10f) * 72f / 25.4f

    /** Fit-width px per pt on the Pad 7 in landscape: (3200 px - 48 dp at 2.75) / A4 width. */
    const val PAD7_FIT_SCALE = (3200f - 48f * 2.75f) / 595.28f

    /** Zoom levels (x fit-width) the invariant must hold at (07-text-engine.md#line-box). */
    val ZOOMS = floatArrayOf(1f, 2f, 4f)

    /** Allowed baseline error in screen px (ADR-008 decision rule). */
    const val MAX_ERROR_PX = 0.5f

    val PARAGRAPHS: List<String> =
        listOf(
            "Handwritten notes and typed text share one page. Every line of this paragraph must sit " +
                "exactly on a rule of the template, no matter which font is chosen or how far the page is zoomed.",
            "Numbers 0123456789, punctuation (a; b: c!) \"quotes\", accents: éèê àâ ü ö ß ñ ç, and " +
                "long words like Donaudampfschifffahrtsgesellschaft wrap without drifting off the grid.",
            "Short line.\nA line after a hard break keeps the pitch; so does the last line of the block, " +
                "which ends with descenders: gjpqy.",
        )

    private const val NS_PER_MS = 1_000_000.0

    /** Measures [family] with [method]; the block top sits on a rule, so line i belongs on rule i + 1. */
    fun run(
        layouter: GridTextLayouter,
        metrics: FontMetricsCache,
        family: BundledFontFamily,
        method: BaselineMethod,
    ): BaselineProbeResult {
        var lines = 0
        var maxError = 0f
        var fontSizePt = 0f
        val timesMs = DoubleArray(PARAGRAPHS.size)
        PARAGRAPHS.forEachIndexed { index, text ->
            val start = System.nanoTime()
            val block = layouter.layout(text, family, UNIT_PT, BODY_M_CAP, WIDTH_PT, method)
            timesMs[index] = (System.nanoTime() - start) / NS_PER_MS
            fontSizePt = block.fontSizePt
            for (line in 0 until block.lineCount) {
                maxError = max(maxError, abs(block.baselinePt(line) - (line + 1) * UNIT_PT))
            }
            lines += block.lineCount
        }
        val proportions = metrics.proportions(family)
        val paint = FontMetricsCache.measurePaint(metrics.typeface(family), fontSizePt * GridTextLayouter.REFERENCE_SCALE)
        val capPt = FontMetricsCache.capHeightPx(paint) / GridTextLayouter.REFERENCE_SCALE
        timesMs.sort()
        return BaselineProbeResult(
            family = family.name,
            method = method,
            lines = lines,
            maxBaselineErrorPt = maxError,
            capErrorPt = abs(capPt - BODY_M_CAP * UNIT_PT),
            fontSizePt = fontSizePt,
            capRatio = proportions.capRatio,
            layoutMedianMs = timesMs[timesMs.size / 2],
        )
    }

    /** A Markdown table of [results]: baseline error in px per zoom, cap error, size, layout time. */
    fun table(results: List<BaselineProbeResult>): String =
        buildString {
            appendLine(
                "| Font | Method | Lines | Max baseline error px z1 / z2 / z4 | Cap error px | Size pt | Cap ratio | Layout ms/para |",
            )
            appendLine("|---|---|---|---|---|---|---|---|")
            for (r in results) {
                val errors = ZOOMS.joinToString(" / ") { "%.3f".format(Locale.ROOT, r.maxBaselineErrorPx(it)) }
                appendLine(
                    "| ${r.family} | ${r.method} | ${r.lines} | $errors | ${"%.3f".format(Locale.ROOT,r.capErrorPx)} | " +
                        "${"%.2f".format(
                            Locale.ROOT,
                            r.fontSizePt,
                        )} | ${"%.3f".format(Locale.ROOT,r.capRatio)} | ${"%.2f".format(Locale.ROOT,r.layoutMedianMs)} |",
                )
            }
        }
}
