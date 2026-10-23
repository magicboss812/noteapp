package dev.folio.core.text.layout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import dev.folio.core.model.GridUnit
import dev.folio.core.text.fonts.BodySize
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Worst baseline errors of one family over the whole corpus, in screen px per zoom of [GridInvariantSuite.ZOOMS]. */
class FamilyInvariant(
    val family: String,
    val blocks: Int,
    val lines: Int,
    /** Text runs drawn (each one's baseline is checked). */
    val runs: Int,
    /** From [BlockLayout.baselinePt] mapped to the screen. */
    val computedPx: FloatArray,
    /** From the y of every recorded drawText call through the canvas matrix. */
    val renderedPx: FloatArray,
    /** Human-readable failures; empty when the invariant holds. */
    val violations: List<String>,
)

/**
 * P06-T03 grid invariant regression suite (07-text-engine.md#line-box), shared by the Robolectric
 * test and the instrumented test on the tablet: every block style of [CORPUS] stacked as a flow on
 * the page grid, for every grid in [GRIDS] and body size in [BODY_CAPS], drawn at Pad 7 screen
 * scale x zoom 1, 2, 4. Each line box must be a whole multiple of U and every drawn baseline must lie
 * within [MAX_ERROR_PX] of its rule.
 */
object GridInvariantSuite {
    /** Fit-width px per pt on the Pad 7 in landscape: (3200 px - 48 dp at 2.75) / A4 width. */
    const val PAD7_FIT_SCALE = (3200f - 48f * 2.75f) / 595.28f

    /** Zoom levels (x fit-width) the invariant must hold at. */
    val ZOOMS = floatArrayOf(1f, 2f, 4f)

    /** Allowed baseline error in screen px (ADR-008). */
    const val MAX_ERROR_PX = 0.5f

    /** Text column: A4 width minus the 25 mm margin line and 12 mm on the right. */
    const val WIDTH_PT = (210f - 25f - 12f) * GridUnit.PT_PER_MM

    /** College ruled (lined, 6 mm indents) and 5 mm graph (column indents), both with a 36 pt top margin. */
    val GRIDS: List<LayoutGrid> =
        listOf(
            LayoutGrid.of(GridUnit(GridUnit.COLLEGE_UNIT_PT, 0f, TOP_MARGIN_PT, snapsColumns = false)),
            LayoutGrid.of(GridUnit(GridUnit.GRAPH_UNIT_PT, 0f, TOP_MARGIN_PT, snapsColumns = true)),
        )

    /** Body S, M, L and a free size whose body line box is 2U (A-039). */
    val BODY_CAPS = floatArrayOf(BodySize.S.capFraction, BodySize.M.capFraction, BodySize.L.capFraction, 1.2f)

    private const val TOP_MARGIN_PT = 36f

    /** Every block style (07-text-engine.md#blocks, #block-styles) with inline styles mixed into the lines. */
    val CORPUS: String =
        listOf(
            "# Heading one is long enough to wrap onto a second line of the frame",
            "## Heading two with **bold** and *italic* words",
            "### Heading three",
            "Handwritten notes and typed text share one page. Every line of this paragraph must sit exactly on a " +
                "rule with **bold**, *italic*, ~~strike~~, `inline code`, ==highlight==, [a link](https://example.org), " +
                "[Other note](folio://doc/7d1c0a4e-0000-4000-8000-000000000001) and \$x^2 + y^2\$ math in the lines.",
            "Numbers 0123456789, punctuation (a; b: c!) \"quotes\", accents: \u00e9\u00e8\u00ea \u00e0\u00e2 \u00fc \u00f6 " +
                "\u00df \u00f1 \u00e7, and long words like Donaudampfschifffahrtsgesellschaft wrap without drifting.",
            "Short line.\\\nA line after a hard break keeps the pitch; so does the last line, with descenders: gjpqy.",
            "\n",
            "- Bullet level one\n  - Bullet level two\n    - Bullet level three is long enough to wrap onto a second line of the list column",
            "1. Ordered item\n  1. Nested ordered item\n    3. Third level item",
            "- [ ] Open task\n- [x] Done task with `code`",
            "> A quote that runs long enough to wrap onto more than one line inside the quote column of the page frame.",
            "> [!NOTE]\n> A callout body under its title line.",
            "```kotlin\nfun main() = println(\"code block\")\nval wrapped = \"a long code line that soft wraps inside the frame when it reaches the edge\"\n```",
            "\$\$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n\$\$",
            "| Name | Value |\n|---|---|\n| one | 1 |\n| two | 2 |",
            "---",
        ).joinToString("\n\n")

    /** Runs the corpus for [familyName] on every grid and body size. */
    fun run(
        layouter: BlockLayouter,
        familyName: String,
    ): FamilyInvariant {
        val blocks = MarkdownDocument.parse(CORPUS).blocks
        val computed = FloatArray(ZOOMS.size)
        val rendered = FloatArray(ZOOMS.size)
        val violations = ArrayList<String>()
        val canvas = RecordingCanvas()
        var lines = 0
        var runs = 0
        var measured = 0
        for (grid in GRIDS) {
            for (bodyCap in BODY_CAPS) {
                val style = FlowTextStyle(familyName = familyName, bodyCap = bodyCap)
                var topUnits = 0 // the flow's first block sits on the rule at the top margin
                for (block in blocks) {
                    val layout = layouter.build(block.content, WIDTH_PT, style, grid)
                    val where = "${grid.unitPt}pt cap $bodyCap ${block.content::class.simpleName} '${block.source.take(LABEL_CHARS)}'"
                    measured++
                    lines += layout.lineCount
                    if (layout.boxUnits < 1 || layout.heightPt != layout.lineCount * layout.boxUnits * grid.unitPt) {
                        violations += "$where: box ${layout.boxUnits}U, height ${layout.heightPt} for ${layout.lineCount} lines"
                    }
                    val topPt = TOP_MARGIN_PT + topUnits * grid.unitPt
                    val pitchPt = layout.boxUnits * grid.unitPt
                    ZOOMS.forEachIndexed { z, zoom ->
                        val scale = PAD7_FIT_SCALE * zoom
                        for (line in 0 until layout.lineCount) {
                            val error = abs((topPt + layout.baselinePt(line)) * scale - (topPt + (line + 1) * pitchPt) * scale)
                            computed[z] = max(computed[z], error)
                            if (error > MAX_ERROR_PX) violations += "$where line $line zoom $zoom: computed error $error px"
                        }
                        canvas.baselines.clear()
                        layout.draw(canvas, 0f, topPt, scale)
                        val hasText = block.content !is BlockContent.Empty && block.content !is BlockContent.Rule
                        if (hasText && canvas.baselines.isEmpty()) violations += "$where zoom $zoom: nothing drawn"
                        if (z == 0) runs += canvas.baselines.size
                        for (y in canvas.baselines) {
                            // Nearest line box bottom; the drawn baseline must sit on it and inside the block.
                            val n = ((y / scale - topPt) / pitchPt).roundToInt()
                            val error = abs(y - (topPt + n * pitchPt) * scale)
                            rendered[z] = max(rendered[z], error)
                            if (n !in 1..layout.lineCount || error > MAX_ERROR_PX) {
                                violations += "$where zoom $zoom: drawn baseline $y px is $error px off rule $n"
                            }
                        }
                    }
                    topUnits += layout.heightUnits
                }
            }
        }
        return FamilyInvariant(familyName, measured, lines, runs, computed, rendered, violations)
    }

    /** A Markdown table of [results]: worst computed / rendered error per zoom. */
    fun table(results: List<FamilyInvariant>): String =
        buildString {
            appendLine("| Font | Blocks | Lines | Runs | Computed px z1 / z2 / z4 | Rendered px z1 / z2 / z4 |")
            appendLine("|---|---|---|---|---|---|")
            for (r in results) {
                appendLine("| ${r.family} | ${r.blocks} | ${r.lines} | ${r.runs} | ${px(r.computedPx)} | ${px(r.renderedPx)} |")
            }
        }

    private fun px(values: FloatArray): String = values.joinToString(" / ") { "%.4f".format(Locale.ROOT, it) }

    private const val LABEL_CHARS = 24

    /**
     * Records the screen y of every text run's baseline (the y argument mapped through the current
     * matrix) instead of rasterizing it; the clip is unbounded so Layout draws every line.
     */
    private class RecordingCanvas : Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)) {
        val baselines = ArrayList<Float>()
        private val matrix = Matrix()
        private val point = FloatArray(2)

        private fun record(
            x: Float,
            y: Float,
        ) {
            @Suppress("DEPRECATION") // getMatrix is the only read of the current transform on a software canvas
            getMatrix(matrix)
            point[0] = x
            point[1] = y
            matrix.mapPoints(point)
            baselines += point[1]
        }

        override fun getClipBounds(bounds: Rect): Boolean {
            bounds.set(-UNBOUNDED, -UNBOUNDED, UNBOUNDED, UNBOUNDED)
            return true
        }

        override fun drawText(
            text: CharArray,
            index: Int,
            count: Int,
            x: Float,
            y: Float,
            paint: Paint,
        ) = record(x, y)

        override fun drawText(
            text: String,
            x: Float,
            y: Float,
            paint: Paint,
        ) = record(x, y)

        override fun drawText(
            text: String,
            start: Int,
            end: Int,
            x: Float,
            y: Float,
            paint: Paint,
        ) = record(x, y)

        override fun drawText(
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            y: Float,
            paint: Paint,
        ) = record(x, y)

        override fun drawTextRun(
            text: CharArray,
            index: Int,
            count: Int,
            contextIndex: Int,
            contextCount: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) = record(x, y)

        override fun drawTextRun(
            text: CharSequence,
            start: Int,
            end: Int,
            contextStart: Int,
            contextEnd: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) = record(x, y)

        override fun drawTextRun(
            text: android.graphics.text.MeasuredText,
            start: Int,
            end: Int,
            contextStart: Int,
            contextEnd: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint,
        ) = record(x, y)

        private companion object {
            const val UNBOUNDED = 1 shl 28
        }
    }
}
