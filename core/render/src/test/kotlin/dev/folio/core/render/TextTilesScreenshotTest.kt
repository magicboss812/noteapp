package dev.folio.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.flow.PageText
import dev.folio.core.text.layout.BlockDecoration
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class TextTilesScreenshotTest {
    @Test
    fun screenshot_textBlockStyles_lined() = capture(TemplateKind.LINED)

    @Test
    fun screenshot_textBlockStyles_grid() = capture(TemplateKind.GRID)

    @Test
    fun screenshot_textBlockStyles_dotted() = capture(TemplateKind.DOTTED)

    @Test
    fun screenshot_mathCorpus_lined() = captureMath(TemplateKind.LINED)

    @Test
    fun screenshot_mathCorpus_grid() = captureMath(TemplateKind.GRID)

    @Test
    fun screenshot_tables_lined() = captureTables(TemplateKind.LINED)

    @Test
    fun screenshot_tables_grid() = captureTables(TemplateKind.GRID)

    @Test
    fun draw_tableBorders_onTheRowRulesAndColumnEdges() {
        val pages = TextPages()
        val content = pages.content("| a | b |\n| --- | --- |\n| 1 | 2 |")
        val run = content.text.runs.single()
        val table = checkNotNull(run.layout.table)
        val bitmap = render(pages, content, RenderTarget.SCREEN_CONTENT)

        // Below the header, halfway across the first column: the row border on the rule.
        val x = ((run.leftPt + table.columnLeftPt(1) / 2f) * SCALE).toInt()
        val y = ((run.topPt + run.layout.lineTopPt(1)) * SCALE).toInt()
        assertThat(Color.alpha(bitmap.getPixel(x, y))).isGreaterThan(0)
        // A column edge halfway down the body row.
        val edge = ((run.leftPt + table.columnLeftPt(1)) * SCALE).toInt()
        val middle = ((run.topPt + run.layout.lineTopPt(1) + run.layout.unitPt / 2f) * SCALE).toInt()
        assertThat(Color.alpha(bitmap.getPixel(edge, middle))).isGreaterThan(0)
    }

    @Test
    fun draw_tableHiddenCell_leavesThatCellBlank_bordersStay() {
        val pages = TextPages()
        val content = pages.content("| a | b |\n| --- | --- |\n| 1 | 2 |")
        val run = content.text.runs.single()
        val table = checkNotNull(run.layout.table)
        val hidden = PageContent.of(pages.page(), PageText.of(listOf(run.copy(hiddenCell = table.cellIndex(1, 1)))))
        val shown = render(pages, content, RenderTarget.SCREEN_CONTENT)
        val blank = render(pages, hidden, RenderTarget.SCREEN_CONTENT)

        val cellTop = run.topPt + run.layout.lineTopPt(1) + 1f
        val cellBottom = run.topPt + run.layout.lineTopPt(2) - 1f
        val left = ((run.leftPt + table.columnLeftPt(1)) * SCALE).toInt() + 2
        val right = ((run.leftPt + table.columnLeftPt(2)) * SCALE).toInt() - 2
        assertThat(inkBounds(shown, cellTop, cellBottom, left, right)).isNotNull()
        assertThat(inkBounds(blank, cellTop, cellBottom, left, right)).isNull()
    }

    @Test
    fun draw_blockMath_formulaInkFollowsTheTextColor() {
        val pages = TextPages()
        val content = pages.content("\$\$\n\\sum_{i=1}^{n} i\n\$\$")
        val run = content.text.runs.single()
        val formula = run.layout.maths.single()

        val bitmap = render(pages, content, RenderTarget.SCREEN_CONTENT)

        val y = ((run.topPt + formula.baselinePt - formula.ascentPt / 2f) * SCALE).toInt()
        val inked = (0 until bitmap.width).map { bitmap.getPixel(it, y) }.filter { Color.alpha(it) == OPAQUE }
        assertThat(inked).isNotEmpty()
        assertThat(inked.distinct()).containsExactly(PageRenderer.textArgb(Color.WHITE))
    }

    @Test
    fun draw_screenContent_paintsTextInsideTheRunBounds() {
        val pages = TextPages()
        val content = pages.content("Alpha")
        val run = content.text.runs.single()

        val bitmap = render(pages, content, RenderTarget.SCREEN_CONTENT)

        val ink = checkNotNull(inkBounds(bitmap))
        val bounds = run.bounds
        assertThat(ink.left.toFloat()).isAtLeast(bounds.left * SCALE - 1)
        assertThat(ink.right.toFloat()).isAtMost(bounds.right * SCALE + 1)
        assertThat(ink.top.toFloat()).isAtLeast(bounds.top * SCALE - 1)
        assertThat(ink.bottom.toFloat()).isAtMost(bounds.bottom * SCALE + 1)
    }

    @Test
    fun draw_backgroundTarget_paintsNoText() {
        val pages = TextPages()
        val content = pages.content("Alpha")
        val withText = render(pages, content, RenderTarget.SCREEN_BACKGROUND)
        val without = render(pages, PageContent.of(pages.page()), RenderTarget.SCREEN_BACKGROUND)

        assertThat(withText.sameAs(without)).isTrue()
    }

    @Test
    fun draw_blockContinuedInAnotherFrame_paintsEachFrameOnlyItsOwnLines() {
        val pages = TextPages()
        val first = pages.box("a", 0, topLines = 0, lines = 2)
        val second = pages.box("b", 1, topLines = GAP_LINES, lines = 2)
        val content = pages.content(LONG_PARAGRAPH, listOf(first, second))
        assertThat(content.text.runs).hasSize(2)

        val bitmap = render(pages, content, RenderTarget.SCREEN_CONTENT)

        // Between the frames (clear of the descender allowance) the continued paragraph leaves no ink.
        val gapTop = first.rect.bottom + content.text.runs[0].overhangPt + 1f
        val gapBottom = second.rect.top - 1f
        assertThat(gapBottom).isGreaterThan(gapTop)
        assertThat(inkBounds(bitmap, gapTop, gapBottom)).isNull()
        assertThat(inkBounds(bitmap, first.rect.top, first.rect.bottom)).isNotNull()
        assertThat(inkBounds(bitmap, second.rect.top, second.rect.bottom)).isNotNull()
    }

    @Test
    fun draw_quoteBar_isPaintedLeftOfTheText() {
        val pages = TextPages()
        val content = pages.content("> Quoted")
        val bitmap = render(pages, content, RenderTarget.SCREEN_CONTENT)

        val run = content.text.runs.single()
        val bar = run.layout.decoration as BlockDecoration.QuoteBar
        val x = run.leftPt + bar.leftPt + bar.widthPt / 2f
        val y = (run.sliceTopPt + run.sliceBottomPt) / 2f
        assertThat(bitmap.getPixel((x * SCALE).toInt(), (y * SCALE).toInt())).isEqualTo(QUOTE_BAR)
    }

    @Test
    fun textArgb_followsPaperLuma() {
        assertThat(PageRenderer.textArgb(Color.WHITE)).isEqualTo(0xFF1B1D22.toInt())
        assertThat(PageRenderer.textArgb(0xFF16181D.toInt())).isEqualTo(0xFFE6E6EA.toInt())
    }

    private fun capture(kind: TemplateKind) {
        val pages = TextPages(kind)
        for ((part, markdown) in listOf("a" to INLINE_AND_LISTS, "b" to BLOCKS)) {
            val content = pages.content(markdown, brokenLinks = setOf(MISSING_DOC))
            render(pages, content, RenderTarget.EXPORT_RASTER).captureRoboImage("src/test/screenshots/TextTiles_${kind}_$part.png")
        }
    }

    private fun captureMath(kind: TemplateKind) {
        val pages = TextPages(kind)
        render(
            pages,
            pages.content(MATH_CORPUS),
            RenderTarget.EXPORT_RASTER,
        ).captureRoboImage("src/test/screenshots/TextTiles_${kind}_math.png")
    }

    private fun captureTables(kind: TemplateKind) {
        val pages = TextPages(kind)
        render(
            pages,
            pages.content(TABLE_CORPUS),
            RenderTarget.EXPORT_RASTER,
        ).captureRoboImage("src/test/screenshots/TextTiles_${kind}_tables.png")
    }

    private fun render(
        pages: TextPages,
        content: PageContent,
        target: RenderTarget,
    ): Bitmap {
        val spec = ModelFixtures.A4
        val bitmap = Bitmap.createBitmap(ceil(spec.widthPt * SCALE).toInt(), ceil(spec.heightPt * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        PageRenderer().draw(Canvas(bitmap), pages.page().toRef(), content, RectPt(0f, 0f, spec.widthPt, spec.heightPt), SCALE, target)
        return bitmap
    }

    /**
     * Pixel box of every non-transparent pixel between page y [fromPt] and [toPt] and pixel columns [fromX] until
     * [toX] (null when there is none).
     */
    private fun inkBounds(
        bitmap: Bitmap,
        fromPt: Float = 0f,
        toPt: Float = Float.MAX_VALUE,
        fromX: Int = 0,
        toX: Int = Int.MAX_VALUE,
    ): Ink? {
        val top = (fromPt * SCALE).toInt().coerceAtLeast(0)
        val bottom = (toPt * SCALE).toInt().coerceAtMost(bitmap.height)
        var ink: Ink? = null
        for (y in top until bottom) {
            for (x in fromX.coerceAtLeast(0) until toX.coerceAtMost(bitmap.width)) {
                if (Color.alpha(bitmap.getPixel(x, y)) == 0) continue
                val old = ink
                ink =
                    if (old ==
                        null
                    ) {
                        Ink(x, y, x, y)
                    } else {
                        Ink(minOf(old.left, x), minOf(old.top, y), maxOf(old.right, x), maxOf(old.bottom, y))
                    }
            }
        }
        return ink
    }

    private data class Ink(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private companion object {
        const val SCALE = 1.5f
        const val GAP_LINES = 6
        const val QUOTE_BAR = 0xFF8E96A3.toInt()
        const val OPAQUE = 0xFF
        const val MISSING_DOC = "missing-id"

        /** Math corpus page (P06-T07): inline math in every context, a tall formula, invalid LaTeX, block math. */
        const val MATH_CORPUS =
            "# Math \$e^{i\\pi} + 1 = 0\$\n\n" +
                "Inline \$x^2 + y^2 = z^2\$, \$\\frac{a}{b}\$, \$\\sqrt{2}\$, \$\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\$ and " +
                "\$\\int_0^\\infty e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}\$ sit on the rules, " +
                "with **bold** text around \$\\vec{F} = m\\vec{a}\$.\n\n" +
                "A tall \$\\frac{1}{1 + \\frac{1}{x}}\$ formula is scaled down and underlined with dots.\n\n" +
                "- Item with \$\\alpha + \\beta\$\n" +
                "- [ ] Task with \$\\binom{n}{k}\$\n\n" +
                "> Quote with \$\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1\$\n\n" +
                "Invalid \$\\notacommand{x}\$ shows red source.\n\n" +
                "\$\$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n\$\$\n\n" +
                "\$\$\n\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix} \\begin{pmatrix} x \\\\ y \\end{pmatrix} = " +
                "\\begin{pmatrix} ax + by \\\\ cx + dy \\end{pmatrix}\n\$\$\n\n" +
                "\$\$\n\\frac{\\notacommand\n\$\$\n\n" +
                "Text after the formulas."

        /** Table corpus page (P06-T08): alignments, wrapping cells, inline formatting and math, missing cells. */
        const val TABLE_CORPUS =
            "Tables sit on the grid.\n\n" +
                "| Item | Qty | Price | Note |\n" +
                "| :-- | :-: | --: | --- |\n" +
                "| Apples | 3 | 1.20 | **fresh** from the market |\n" +
                "| Pears | 12 | 0.80 | a much longer note that wraps over several lines inside its cell |\n" +
                "| Plums | 7 | 2.50 | `code`, *italic* and \$x^2\$ |\n\n" +
                "| Only a header |\n| --- |\n\n" +
                "| a | b | c |\n| --- | --- | --- |\n| 1 |\n| x \\| y | | z |\n\n" +
                "Text after the tables."
        val LONG_PARAGRAPH = "lorem ipsum dolor sit amet ".repeat(20).trim()

        val INLINE_AND_LISTS =
            """
            # Heading one
            ## Heading two
            ### Heading three

            Plain text with **bold**, *italic*, ~~strike~~, `code`, ==highlight== and a [link](https://example.org) in it.

            Note links: [Physics](folio://doc/physics-id), [[Wiki title]] and a broken [Old note](folio://doc/$MISSING_DOC).

            - Bullet one
              - Nested bullet
            - Bullet two

            1. Ordered one
              1. Nested ordered
              2. Nested again
                1. Third level
            2. Ordered two

            - [ ] Open task
            - [x] Done task
            """.trimIndent()

        const val BLOCKS =
            "> A quote on the grid\n\n" +
                "> [!NOTE]\n> A note callout\n\n" +
                "> [!WARNING]\n> A warning callout\n\n" +
                "```\nval code = 1\nfun block() = code\n```\n\n" +
                "\$\$\nx^2 + y^2 = z^2\n\$\$\n\n" +
                "| a | b |\n|---|---|\n| 1 | 2 |\n\n" +
                "---\n\nAfter the rule"
    }
}
