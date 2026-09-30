package dev.folio.core.render.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.InkPainter
import dev.folio.core.render.PageContent
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.core.render.viewport.ZoomBuckets
import dev.folio.core.testing.ModelFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TileLayerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val pool = BitmapPool()
    private val painter = CountingPainter()
    private var ready = 0
    private val a4 = ModelFixtures.page("p")
    private val frame = RectPt(0f, 0f, a4.spec.widthPt, a4.spec.heightPt)

    @Test
    fun request_contentLayer_rendersOnlyTilesWithObjects() {
        val layer = layer(RenderTarget.SCREEN_CONTENT)
        layer.setPage(a4.toRef(), a4.withStrokes(stroke("s1", 100f, 100f)))

        layer.request(listOf(visible(frame)), scale = 1f) // bucket 0: 512 pt tiles, 2 x 2 on A4
        scope.testScheduler.advanceUntilIdle()

        assertThat(layer.tileCount).isEqualTo(4)
        assertThat(layer.bytes).isEqualTo(TileGrid.TILE_BYTES)
        assertThat(painter.draws).containsExactly("s1")
        assertThat(pool.created).isEqualTo(1)
        assertThat(layer.isSettled).isTrue()
        assertThat(ready).isEqualTo(1)
    }

    @Test
    fun request_blankBackground_rendersNothing() {
        val layer = layer(RenderTarget.SCREEN_BACKGROUND)
        val blank =
            a4.copy(
                background = a4.background.copy(template = a4.background.template.copy(kind = dev.folio.core.model.TemplateKind.BLANK)),
            )
        layer.setPage(blank.toRef(), null)

        layer.request(listOf(visible(frame)), scale = 1f)
        scope.testScheduler.advanceUntilIdle()

        assertThat(layer.tileCount).isEqualTo(4)
        assertThat(layer.bytes).isEqualTo(0)
        assertThat(pool.created).isEqualTo(0)
    }

    @Test
    fun setPage_addedStroke_rerendersOnlyTheTileUnderIt() {
        val layer = layer(RenderTarget.SCREEN_CONTENT)
        val first = a4.withStrokes(stroke("s1", 100f, 100f))
        layer.setPage(first.toRef(), first)
        layer.request(listOf(visible(frame)), scale = 1f)
        scope.testScheduler.advanceUntilIdle()

        val second = first.withStrokes(first.objects[0] as InkStroke, stroke("s2", 550f, 600f))
        layer.setPage(second.toRef(), second)
        layer.request(listOf(visible(frame)), scale = 1f)
        scope.testScheduler.advanceUntilIdle()

        assertThat(painter.draws).containsExactly("s1", "s2").inOrder()
        assertThat(layer.bytes).isEqualTo(2 * TileGrid.TILE_BYTES)
    }

    @Test
    fun isDrawn_addedStroke_falseUntilTheTileUnderItIsRerendered() {
        val layer = layer(RenderTarget.SCREEN_CONTENT)
        val first = a4.withStrokes(stroke("s1", 100f, 100f))
        layer.setPage(first.toRef(), first)
        layer.request(listOf(visible(frame)), scale = 1f)
        scope.testScheduler.advanceUntilIdle()
        val s2 = stroke("s2", 550f, 600f)
        val b = s2.bounds

        val second = first.withStrokes(first.objects[0] as InkStroke, s2)
        layer.setPage(second.toRef(), second)
        val beforeRender = layer.isDrawn("p", b.left, b.top, b.right, b.bottom, scale = 1f)
        layer.request(listOf(visible(frame)), scale = 1f)
        val whileRendering = layer.isDrawn("p", b.left, b.top, b.right, b.bottom, scale = 1f)
        scope.testScheduler.advanceUntilIdle()

        assertThat(beforeRender).isFalse()
        assertThat(whileRendering).isFalse()
        assertThat(layer.isDrawn("p", b.left, b.top, b.right, b.bottom, scale = 1f)).isTrue()
        assertThat(layer.isDrawn("p", b.left, b.top, b.right, b.bottom, scale = 2f)).isFalse() // other bucket: no tiles
        assertThat(layer.isDrawn("p", 10f, 10f, 10f, 10f, scale = 2f)).isTrue() // empty rect
    }

    @Test
    fun request_newBucket_cancelsPendingRendersOfTheOldBucket() {
        val layer = layer(RenderTarget.SCREEN_BACKGROUND)
        layer.setPage(a4.toRef(), null)
        layer.request(listOf(visible(frame)), scale = 1f)
        assertThat(layer.pendingCount).isEqualTo(4)

        layer.request(listOf(visible(RectPt(0f, 0f, 200f, 200f))), scale = 2f) // bucket 2: one 256 pt tile
        scope.testScheduler.advanceUntilIdle()

        assertThat(layer.tileCount).isEqualTo(1)
        assertThat(pool.pooled).isEqualTo(4) // cancelled renders returned their bitmaps
    }

    @Test
    fun prefetch_ringAroundVisibleTiles_onlyAfterRequest_andWithinBudget() {
        val layer = layer(RenderTarget.SCREEN_BACKGROUND)
        layer.setPage(a4.toRef(), null)
        val view = RectPt(0f, 0f, 200f, 200f) // bucket 2: tile (0, 0)
        val around = VisiblePage("p", view, 100f, 100f, RectPt(0f, 0f, 460f, 460f))

        layer.request(listOf(around), scale = 2f)
        assertThat(layer.pendingCount).isEqualTo(1)
        layer.prefetch(listOf(around), scale = 2f)
        scope.testScheduler.advanceUntilIdle()

        assertThat(layer.tileCount).isEqualTo(4)
    }

    @Test
    fun warmUp_preparesEveryObjectOfVisiblePages() {
        val layer = layer(RenderTarget.SCREEN_CONTENT)
        val page = a4.withStrokes(stroke("s1", 100f, 100f), stroke("s2", 500f, 800f))
        layer.setPage(page.toRef(), page)

        layer.warmUp(listOf(visible(frame)))
        scope.testScheduler.advanceUntilIdle()

        assertThat(painter.prepared).containsExactly("s1", "s2")
    }

    @Test
    fun draw_missingCurrentBucket_fallsBackToOtherBucketTiles() {
        val layer = layer(RenderTarget.SCREEN_CONTENT)
        val page = a4.withStrokes(stroke("s1", 100f, 100f))
        layer.setPage(page.toRef(), page)
        layer.request(listOf(visible(frame)), scale = 1f)
        scope.testScheduler.advanceUntilIdle()

        val out = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        layer.beginFrame()
        layer.draw(Canvas(out), "p", 0f, 0f, 2f, 0f, 0f, 200f, 200f) // bucket 2 not rendered yet

        assertThat(Color.alpha(out.getPixel(205, 205))).isGreaterThan(0) // the stroke at (100, 100) pt, 2x
        assertThat(Color.alpha(out.getPixel(20, 20))).isEqualTo(0)
    }

    @Test
    fun draw_tilesAtBucketScale_matchDirectRenderPixelForPixel() {
        val layer = layer(RenderTarget.SCREEN_BACKGROUND, painterFactory = { PageRenderer() })
        layer.setPage(a4.toRef(), null)
        val scale = ZoomBuckets.scaleOf(3) // 2.83 px/pt: 4 x 5 tiles of 181 pt
        layer.request(listOf(visible(frame)), scale)
        scope.testScheduler.advanceUntilIdle()
        val w = (frame.right * scale).toInt()
        val h = (frame.bottom * scale).toInt()

        val tiled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        layer.beginFrame()
        layer.draw(Canvas(tiled), "p", 0f, 0f, scale, 0f, 0f, frame.right, frame.bottom)
        val direct = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PageRenderer().draw(Canvas(direct), a4.toRef(), null, frame, scale, RenderTarget.SCREEN_BACKGROUND)

        var differing = 0
        for (y in 0 until h) for (x in 0 until w) if (tiled.getPixel(x, y) != direct.getPixel(x, y)) differing++
        assertThat(differing.toDouble() / (w * h)).isLessThan(MAX_DIFF_SHARE)
    }

    private fun layer(
        target: RenderTarget,
        painterFactory: () -> PageRenderer = { PageRenderer(ink = painter) },
    ) = TileLayer(target, BUDGET, pool, scope, dispatcher, painterFactory) { ready++ }

    private fun visible(rect: RectPt) = VisiblePage("p", rect, rect.center.x, rect.center.y, rect)

    private fun Page.withStrokes(vararg strokes: InkStroke): Page = copy(objects = persistentListOf(*strokes))

    private fun stroke(
        id: String,
        x: Float,
        y: Float,
    ): InkStroke {
        val inputs =
            StrokeInputs(floatArrayOf(x, x + 10f), floatArrayOf(y, y + 10f), floatArrayOf(0f, 5f), null, null, null, InputTool.SYNTHETIC)
        return InkStroke.of(ObjectId(id), BrushSpec(BrushKind.BALLPOINT, Color.BLACK, 4f, 1), inputs)
    }

    /** Fills stroke bounds and records which strokes were drawn (render threads are the test thread here). */
    private class CountingPainter : InkPainter {
        val draws = ArrayList<String>()
        val prepared = ArrayList<String>()
        private val paint = Paint().apply { color = Color.BLACK }

        override fun prepare(
            stroke: InkStroke,
            content: PageContent,
            index: Int,
        ) {
            prepared += stroke.id.value
        }

        override fun draw(
            canvas: Canvas,
            stroke: InkStroke,
            content: PageContent,
            index: Int,
            toDevice: Matrix,
        ) {
            draws += stroke.id.value
            val b = stroke.bounds
            canvas.drawRect(b.left, b.top, b.right, b.bottom, paint)
        }
    }

    private companion object {
        const val BUDGET = 64 * TileGrid.TILE_BYTES
        const val MAX_DIFF_SHARE = 1e-4
    }
}
