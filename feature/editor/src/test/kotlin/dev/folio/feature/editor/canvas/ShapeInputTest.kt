package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.PageId
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import dev.folio.feature.editor.state.ShapeFill
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.atan2

@RunWith(AndroidJUnit4::class)
class ShapeInputTest {
    private val pages = List(3) { ModelFixtures.page("p$it").toRef() }
    private val viewport = Viewport(DENSITY).apply { setPages(pages, VIEW_W, VIEW_H) }
    private var spec = ShapeSpec(ShapeKind.RECTANGLE, STROKE, ShapeFill.TINT.argb(STROKE.argb), constrain = false)
    private val commits = ArrayList<Pair<PageId, Shape>>()
    private var invalidations = 0
    private val input =
        ShapeInput(
            viewport,
            pageIdAt = { pages.getOrNull(it)?.id },
            spec = { spec },
            closeRadiusPx = CLOSE_PX,
            doubleTapMs = DOUBLE_TAP_MS,
            invalidate = { invalidations++ },
            onCommit = { page, shape -> commits += page to shape },
        )

    @Test
    fun drag_rectangle_commitsOnThePageInPagePoints_previewFollowsThePen() {
        drag(PointPt(100f, 100f), PointPt(160f, 140f), page = 1)

        val (page, shape) = commits.single()
        assertThat(page).isEqualTo(PageId("p1"))
        assertThat(shape.kind).isEqualTo(ShapeKind.RECTANGLE)
        assertNear(shape.points[0], PointPt(100f, 100f))
        assertNear(shape.points[1], PointPt(160f, 140f))
        assertThat(shape.fillArgb).isEqualTo(0x401A1A1A)
        assertThat(input.pending?.shape).isEqualTo(shape)
        assertThat(input.draft()).isNull() // the pen is up: only the pending shape is drawn
        assertThat(invalidations).isAtLeast(3)

        input.committed()
        assertThat(input.pending).isNull()
    }

    @Test
    fun drag_whileMoving_draftIsTheShapeSoFar() {
        val page = PageId("p0")
        input.onStylusDown(stylus(ACTION_DOWN, 0, at(page, PointPt(50f, 50f))), 0, eraser = false)
        input.onStylusMove(stylus(ACTION_MOVE, 10, at(page, PointPt(90f, 80f))), 0)

        assertThat(input.draftPage).isEqualTo(0)
        assertNear(input.draft()!!.points[1], PointPt(90f, 80f))
    }

    @Test
    fun drag_tooShortOrEraser_commitsNothing() {
        drag(PointPt(100f, 100f), PointPt(100.5f, 100.5f))
        val page = PageId("p0")
        input.onStylusDown(stylus(ACTION_DOWN, 0, at(page, PointPt(10f, 10f))), 0, eraser = true)
        input.onStylusUp(stylus(ACTION_UP, 10, at(page, PointPt(90f, 90f))), 0)

        assertThat(commits).isEmpty()
    }

    @Test
    fun drag_lineWithStylusButton_snapsTo15Degrees() {
        spec = spec.copy(kind = ShapeKind.LINE)
        drag(PointPt(100f, 100f), PointPt(200f, 122f), button = MotionEvent.BUTTON_STYLUS_PRIMARY)

        val end = commits.single().second.points[1]
        val deg = Math.toDegrees(atan2((end.y - 100f).toDouble(), (end.x - 100f).toDouble()))
        assertThat(deg).isWithin(0.01).of(15.0)
        assertThat(commits.single().second.fillArgb).isNull() // lines never fill
    }

    @Test
    fun polygon_tapsThenTapOnFirstVertex_closesIntoOneShape() {
        spec = spec.copy(kind = ShapeKind.POLYGON)
        tap(PointPt(100f, 100f), 0)
        tap(PointPt(200f, 100f), 1000)
        assertThat(input.draft()?.kind).isEqualTo(ShapeKind.LINE) // two vertices: the first side
        tap(PointPt(150f, 180f), 2000)
        assertThat(input.draft()?.kind).isEqualTo(ShapeKind.POLYGON)
        assertThat(commits).isEmpty()

        tap(PointPt(101f, 101f), 3000)

        assertThat(commits.single().second.points).hasSize(3)
        assertThat(input.polygonVertices).isEmpty()
    }

    @Test
    fun polygonDraft_doubleTapOnLastVertex_closes_tooFewVerticesDoNot() {
        val draft = PolygonDraft()
        assertThat(draft.tap(0, PointPt(0f, 0f), 0, 5f, DOUBLE_TAP_MS)).isNull()
        assertThat(draft.tap(0, PointPt(0f, 0f), 100, 5f, DOUBLE_TAP_MS)).isNull() // a double tap at 1 vertex adds nothing
        assertThat(draft.vertices).hasSize(1)
        draft.tap(0, PointPt(50f, 0f), 1000, 5f, DOUBLE_TAP_MS)
        draft.tap(0, PointPt(50f, 50f), 2000, 5f, DOUBLE_TAP_MS)

        val closed = draft.tap(0, PointPt(51f, 50f), 2100, 5f, DOUBLE_TAP_MS)

        assertThat(closed).containsExactly(PointPt(0f, 0f), PointPt(50f, 0f), PointPt(50f, 50f)).inOrder()
        assertThat(draft.isEmpty).isTrue()
    }

    @Test
    fun polygonDraft_tapOnAnotherPage_startsOver() {
        val draft = PolygonDraft()
        draft.tap(0, PointPt(0f, 0f), 0, 5f, DOUBLE_TAP_MS)
        draft.tap(0, PointPt(50f, 0f), 1000, 5f, DOUBLE_TAP_MS)
        draft.tap(1, PointPt(10f, 10f), 2000, 5f, DOUBLE_TAP_MS)

        assertThat(draft.vertices).containsExactly(PointPt(10f, 10f))
        assertThat(draft.pageIndex).isEqualTo(1)
    }

    @Test
    fun cancelDraft_dropsVertices_keepsPending() {
        drag(PointPt(100f, 100f), PointPt(160f, 140f))
        spec = spec.copy(kind = ShapeKind.POLYGON)
        tap(PointPt(10f, 10f), 5000)

        input.cancelDraft()

        assertThat(input.polygonVertices).isEmpty()
        assertThat(input.pending).isNotNull()
        input.reset()
        assertThat(input.pending).isNull()
    }

    private fun drag(
        from: PointPt,
        to: PointPt,
        page: Int = 0,
        button: Int = 0,
    ) {
        val id = pages[page].id
        input.onStylusDown(stylus(ACTION_DOWN, 0, at(id, from), button), 0, eraser = false)
        input.onStylusMove(stylus(ACTION_MOVE, 10, at(id, to), button), 0)
        input.onStylusUp(stylus(ACTION_UP, 20, at(id, to), button), 0)
    }

    private fun tap(
        p: PointPt,
        timeMs: Long,
    ) {
        val px = at(pages[0].id, p)
        input.onStylusDown(stylus(ACTION_DOWN, timeMs, px), 0, eraser = false)
        input.onStylusUp(stylus(ACTION_UP, timeMs + 50, px), 0)
    }

    private fun at(
        page: PageId,
        p: PointPt,
    ): Pair<Float, Float> = viewport.toViewPx(page, p).let { it.x to it.y }

    private fun stylus(
        action: Int,
        timeMs: Long,
        px: Pair<Float, Float>,
        button: Int = 0,
    ) = event(action, timeMs, px, toolType = TOOL_TYPE_STYLUS, buttonState = button)

    private fun assertNear(
        actual: PointPt,
        expected: PointPt,
    ) {
        assertThat(actual.x).isWithin(0.01f).of(expected.x)
        assertThat(actual.y).isWithin(0.01f).of(expected.y)
    }

    private companion object {
        const val DENSITY = 2.5f
        const val VIEW_W = 2136f
        const val VIEW_H = 3200f
        const val CLOSE_PX = 30f
        const val DOUBLE_TAP_MS = 300L
        val STROKE = StrokeStyle(0xFF1A1A1A.toInt(), 2f, dashed = false)
    }
}
