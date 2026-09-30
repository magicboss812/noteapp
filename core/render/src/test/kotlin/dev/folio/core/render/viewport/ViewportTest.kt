package dev.folio.core.render.viewport

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class ViewportTest {
    private val a4 = ModelFixtures.A4
    private val fit = (VIEW_W - 2 * 24f * DENSITY) / a4.widthPt
    private val cardStepPx = a4.heightPt * fit + 16f * DENSITY

    private fun pages(count: Int): List<PageRef> = List(count) { ModelFixtures.page("p$it").toRef() }

    private fun viewport(pages: List<PageRef> = pages(200)): Viewport = Viewport(DENSITY).apply { setPages(pages, VIEW_W, VIEW_H) }

    @Test
    fun setPages_firstCall_showsFitWidthAtFirstPageTop() {
        val vp = viewport()

        assertThat(vp.scale).isWithin(1e-4f).of(fit)
        assertThat(vp.fitWidthScale).isWithin(1e-4f).of(fit)
        assertThat(vp.offsetXPx).isWithin(1e-3).of(0.0)
        assertThat(vp.offsetYPx).isEqualTo(0.0)
        assertThat(vp.bucketIndex).isEqualTo(ZoomBuckets.indexFor(fit))
    }

    @Test
    fun toViewPx_fitWidth_pageInsetBySidePaddingAndGap() {
        val vp = viewport()

        val topLeft = vp.toViewPx(PageId("p0"), PointPt(0f, 0f))
        val bottomRight = vp.toViewPx(PageId("p0"), PointPt(a4.widthPt, a4.heightPt))
        val secondTop = vp.toViewPx(PageId("p1"), PointPt(0f, 0f))

        assertThat(topLeft.x).isWithin(0.01f).of(24f * DENSITY)
        assertThat(topLeft.y).isWithin(0.01f).of(16f * DENSITY)
        assertThat(bottomRight.x).isWithin(0.01f).of(VIEW_W - 24f * DENSITY)
        assertThat(secondTop.y - topLeft.y).isWithin(0.01f).of(cardStepPx)
    }

    @Test
    fun toPagePt_randomPointsAndZooms_roundTripsWithin0_01pt() {
        val seed = 7L
        val random = Random(seed)
        val vp = viewport()
        repeat(500) {
            vp.zoomBy(0.5f + random.nextFloat() * 1.5f, random.nextFloat() * VIEW_W, random.nextFloat() * VIEW_H)
            vp.panBy(random.nextFloat() * 4000f - 2000f, random.nextFloat() * 40_000f - 20_000f)
            val id = PageId("p${random.nextInt(200)}")
            val pt = PointPt(random.nextFloat() * a4.widthPt, random.nextFloat() * a4.heightPt)

            val back = vp.toPagePt(id, vp.toViewPx(id, pt))

            assertWithMessage("seed=$seed").that(back.x).isWithin(0.01f).of(pt.x)
            assertWithMessage("seed=$seed").that(back.y).isWithin(0.05f).of(pt.y)
        }
    }

    @Test
    fun zoomBy_clampsToRangeOfFitWidth() {
        val vp = viewport()

        vp.zoomBy(100f, 500f, 500f)
        assertThat(vp.scale).isWithin(1e-3f).of(8f * fit)
        vp.zoomBy(0.0001f, 500f, 500f)
        assertThat(vp.scale).isWithin(1e-4f).of(0.2f * fit)
    }

    @Test
    fun zoomBy_zoomedOut_centersColumnHorizontally() {
        val vp = viewport()

        vp.zoomBy(0.5f, 100f, 100f)

        val left = vp.toViewPx(PageId("p0"), PointPt(0f, 0f)).x
        val right = vp.toViewPx(PageId("p0"), PointPt(a4.widthPt, 0f)).x
        assertThat(left).isWithin(0.01f).of(VIEW_W - right)
    }

    @Test
    fun panBy_pastEnds_leavesAtMostHalfAScreenEmpty() {
        val vp = viewport()

        vp.panBy(0f, 1_000_000f)
        assertThat(vp.toViewPx(PageId("p0"), PointPt(0f, 0f)).y).isWithin(0.5f).of(VIEW_H / 2f + 16f * DENSITY)
        vp.panBy(0f, -10_000_000f)
        val lastBottom = vp.toViewPx(PageId("p199"), PointPt(0f, a4.heightPt)).y
        assertThat(lastBottom).isWithin(0.5f).of(VIEW_H / 2f - 16f * DENSITY)
    }

    @Test
    fun panBy_zoomedIn_clampsHorizontallyToColumnEdges() {
        val vp = viewport()
        vp.zoomBy(3f, 0f, 0f)

        vp.panBy(10_000f, 0f)
        assertThat(vp.offsetXPx).isEqualTo(0.0)
        vp.panBy(-100_000f, 0f)
        assertThat(vp.offsetXPx).isWithin(0.5).of(VIEW_W - VIEW_W * 3.0)
    }

    @Test
    fun zoomBy_stackZoomInMidDocument_keepsFocalPointWithinHalfPixel() {
        val seed = 11L
        val random = Random(seed)
        val vp = viewport()
        vp.panBy(0f, -100 * cardStepPx)
        repeat(40) {
            val fx = random.nextFloat() * VIEW_W
            val fy = random.nextFloat() * VIEW_H
            val id = PageId("p${vp.visiblePages().first}")
            val under = vp.toPagePt(id, PointPx(fx, fy))

            vp.zoomBy(1f + random.nextFloat() * 0.1f, fx, fy)

            val moved = vp.toViewPx(id, under)
            assertWithMessage("seed=$seed step=$it").that(hypot(moved.x - fx, moved.y - fy)).isAtMost(0.5f)
        }
    }

    @Test
    fun zoomBy_canvasRandomPinches_keepsFocalPointWithinHalfPixel() {
        val seed = 13L
        val random = Random(seed)
        val id = PageId("inf")
        val vp = viewport(listOf(PageRef(id, PageSpec.Infinite(a4), ModelFixtures.LINED_BACKGROUND, null)))
        vp.show(ViewportMode.Canvas(id))
        repeat(500) {
            val fx = random.nextFloat() * VIEW_W
            val fy = random.nextFloat() * VIEW_H
            val under = vp.toPagePt(id, PointPx(fx, fy))

            vp.zoomBy(0.8f + random.nextFloat() * 0.45f, fx, fy)

            val moved = vp.toViewPx(id, under)
            assertWithMessage("seed=$seed step=$it").that(hypot(moved.x - fx, moved.y - fy)).isAtMost(0.5f)
            // Two-finger gestures pan between pinch steps.
            vp.panBy(random.nextFloat() * 200f - 100f, random.nextFloat() * 200f - 100f)
        }
    }

    @Test
    fun visiblePages_fitWidthAtTop_showsFirstTwoPages() {
        val vp = viewport()

        assertThat(vp.visiblePages()).isEqualTo(0..1)
    }

    @Test
    fun visiblePages_200PagesRandomViewports_matchesBruteForce() {
        val seed = 17L
        val random = Random(seed)
        val vp = viewport()
        repeat(300) {
            vp.zoomBy(0.5f + random.nextFloat() * 1.5f, random.nextFloat() * VIEW_W, random.nextFloat() * VIEW_H)
            vp.panBy(0f, random.nextFloat() * 200_000f - 100_000f)

            val expected = (0 until 200).filter { !vp.visibleRectPt(PageId("p$it")).isEmpty }

            assertWithMessage("seed=$seed step=$it").that(vp.visiblePages().toList()).isEqualTo(expected)
        }
    }

    @Test
    fun visiblePages_minZoomMidDocument_listsContiguousRange() {
        val vp = viewport()
        vp.zoomBy(0.0001f, 0f, 0f)
        vp.panBy(0f, -100 * cardStepPx * 0.2f)

        val range = vp.visiblePages()

        assertThat(range.count()).isGreaterThan(5)
        assertThat(range.first).isGreaterThan(90)
        assertThat(range.last).isLessThan(120)
    }

    @Test
    fun visibleRectPt_fitWidthAtTop_clipsToPageFrame() {
        val vp = viewport()

        assertThat(vp.visibleRectPt(PageId("p0"))).isEqualTo(RectPt(0f, 0f, a4.widthPt, a4.heightPt))
        val second = vp.visibleRectPt(PageId("p1"))
        assertThat(second.top).isEqualTo(0f)
        assertThat(second.bottom).isWithin(0.01f).of((VIEW_H - 16f * DENSITY - cardStepPx) / fit)
        assertThat(vp.visibleRectPt(PageId("p5")).isEmpty).isTrue()
    }

    @Test
    fun cardFrame_infinitePage_growsByContentBoundsIncludingNegative() {
        val id = PageId("inf")
        val content = RectPt(-100f, -50f, 700f, 2000f)
        val vp =
            viewport(listOf(ModelFixtures.page("p0").toRef(), PageRef(id, PageSpec.Infinite(a4), ModelFixtures.LINED_BACKGROUND, content)))
        val layout = requireNotNull(vp.layout)

        val card = layout.cardRectPt(1)
        val cardTopLeft = vp.toViewPx(id, PointPt(-100f, -50f))

        assertThat(card.widthPt).isWithin(1e-3f).of(800f)
        assertThat(card.heightPt).isWithin(1e-3f).of(2050f)
        assertThat(cardTopLeft.x.toDouble()).isWithin(0.01).of(vp.offsetXPx + card.left * vp.scale)
        assertThat(cardTopLeft.y.toDouble()).isWithin(0.01).of(vp.offsetYPx + card.top * vp.scale)
        assertThat(vp.fitWidthScale).isWithin(1e-4f).of((VIEW_W - 2 * 24f * DENSITY) / 800f)
    }

    @Test
    fun show_canvas_fitsOriginWidthAndPansFreely() {
        val id = PageId("inf")
        val vp = viewport(listOf(PageRef(id, PageSpec.Infinite(a4), ModelFixtures.LINED_BACKGROUND, null)))

        vp.show(ViewportMode.Canvas(id))
        vp.panBy(1_000_000f, 1_000_000f)

        assertThat(vp.scale).isWithin(1e-4f).of(fit)
        assertThat(vp.offsetXPx).isWithin(0.01).of(24.0 * DENSITY + 1_000_000.0)
        assertThat(vp.visiblePages()).isEqualTo(0..0)
        assertThat(vp.visibleRectPt(id).right).isLessThan(0f)
    }

    @Test
    fun setPages_rotation_keepsZoomRelativeToFitWidth() {
        val vp = viewport()
        vp.zoomBy(2f, VIEW_W / 2f, VIEW_H / 2f)

        vp.setPages(pages(200), VIEW_H, VIEW_W)

        assertThat(vp.scale / vp.fitWidthScale).isWithin(1e-4f).of(2f)
        assertThat(vp.fitWidthScale).isWithin(1e-4f).of((VIEW_H - 2 * 24f * DENSITY) / a4.widthPt)
    }

    @Test
    fun zoomTo_absoluteZoom_setsZoomAndKeepsFocus() {
        val vp = viewport()
        val id = PageId("p0")
        val focus = PointPx(VIEW_W / 2f, 900f)
        val before = vp.toPagePt(id, focus)

        vp.zoomTo(3f, focus.x, focus.y)

        assertThat(vp.zoom).isWithin(1e-4f).of(3f)
        val after = vp.toViewPx(id, before)
        assertThat(hypot(after.x - focus.x, after.y - focus.y)).isLessThan(0.5f)
    }

    @Test
    fun docToView_matchesToViewPx() {
        val vp = viewport()
        vp.zoomBy(2.5f, 100f, 2000f)
        vp.panBy(0f, -50_000f)
        val stack = requireNotNull(vp.layout)

        val corner = vp.toViewPx(PageId("p30"), PointPt(0f, 0f))

        assertThat(vp.docToViewX(stack.cardLeftPt(30)).toFloat()).isWithin(0.01f).of(corner.x)
        assertThat(vp.docToViewY(stack.cardTopPt(30)).toFloat()).isWithin(0.01f).of(corner.y)
    }

    @Test
    fun offsetYForPageTop_page10_putsCardTopOneGapBelowViewTop() {
        val vp = viewport()

        vp.panBy(0f, (vp.offsetYForPageTop(10) - vp.offsetYPx).toFloat())

        assertThat(vp.toViewPx(PageId("p10"), PointPt(0f, 0f)).y).isWithin(0.05f).of(16f * DENSITY)
    }

    @Test
    fun pageIndexAt_cardsGapsAndEnds_picksCardUnderOrNearest() {
        val vp = viewport(pages(5))
        vp.zoomTo(0.2f, 0f, 0f)
        val stack = requireNotNull(vp.layout)

        fun yOf(yPt: Float) = vp.docToViewY(yPt).toFloat()
        val bottom1 = stack.cardTopPt(1) + a4.heightPt

        assertThat(vp.pageIndexAt(yOf(stack.cardTopPt(2) + 10f))).isEqualTo(2)
        assertThat(vp.pageIndexAt(yOf(bottom1 + stack.gapPt * 0.25f))).isEqualTo(1)
        assertThat(vp.pageIndexAt(yOf(bottom1 + stack.gapPt * 0.75f))).isEqualTo(2)
        assertThat(vp.pageIndexAt(yOf(-100f))).isEqualTo(0)
        assertThat(vp.pageIndexAt(yOf(stack.contentHeightPt + 100f))).isEqualTo(4)
    }

    @Test
    fun pageOriginView_matchesToViewPx() {
        val vp = viewport()
        vp.zoomBy(2.5f, 100f, 2000f)
        vp.panBy(0f, -50_000f)

        val corner = vp.toViewPx(PageId("p30"), PointPt(0f, 0f))

        assertThat(vp.pageOriginViewX(30).toFloat()).isWithin(0.01f).of(corner.x)
        assertThat(vp.pageOriginViewY(30).toFloat()).isWithin(0.01f).of(corner.y)
    }

    private companion object {
        const val DENSITY = 2.5f
        const val VIEW_W = 2136f
        const val VIEW_H = 3200f
    }
}
