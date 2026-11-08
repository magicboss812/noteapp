package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.shape.DwellDetector
import dev.folio.core.ink.shape.RecognizedShape
import dev.folio.core.ink.shape.ShapeRecognizer
import dev.folio.core.ink.shape.StraightStroke
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.shape.ShapeGeometry
import kotlin.math.hypot

/** A snapped pen stroke committed on page [pageId] (stack [pageIndex]) that the preview draws until its tiles show it. */
internal data class PendingSnap(
    val pageId: PageId,
    val pageIndex: Int,
    val obj: PageObject,
)

/** When the hold check runs: the host's main-thread clock and delayed posts (View.postDelayed in the app). */
internal interface HoldTimer {
    /** Now, on the clock of MotionEvent times (uptime ms). */
    fun nowMs(): Long

    /** Runs [task] on the main thread after [delayMs]. */
    fun post(
        task: Runnable,
        delayMs: Long,
    )

    /** Drops a posted [task]. */
    fun cancel(task: Runnable)
}

/**
 * Hold to snap for the pen (06-ink-input.md#shape-recognition, A-065): while a stroke is drawn it records the samples
 * (page pt) and watches for a hold ([DwellDetector], 4 dp for 450 ms, checked by [timer]). At a hold the stroke up
 * to it becomes a recognized shape (pen kinds; no fit: it stays ink) or, for the highlighter, a straight line from
 * its first sample; [onSnapped] tells the pen to drop the wet stroke. From then on the pen adjusts it ([preview] once
 * per frame) and [finish] hands the object to [onFinished]; it stays [pending] until [committed]. A straight-only
 * highlighter (D-024) snaps at [begin].
 */
@MainThread
@Suppress("TooManyFunctions") // stroke recording, hold timer, preview and commit share the stroke state
internal class PenSnap(
    dwellRadiusPx: Float,
    private val minShapePx: Float,
    private val timer: HoldTimer,
    private val newId: () -> ObjectId = ObjectId::random,
) {
    private val dwell = DwellDetector(dwellRadiusPx)
    private val check = Runnable(::onTimer)
    private var xs = FloatArray(INITIAL_SAMPLES)
    private var ys = FloatArray(INITIAL_SAMPLES)
    private var count = 0
    private var active = false
    private var spec: BrushSpec? = null
    private var pageId: PageId? = null
    private var page = -1
    private var originX = 0f
    private var originY = 0f
    private var scale = 1f
    private var startMs = 0L
    private var lastMs = 0L
    private var shape: RecognizedShape? = null
    private var straight = false
    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f

    /** The pen dropped its wet stroke: the snap took over (set by the pen). */
    var onSnapped: () -> Unit = {}

    /** The preview changed (set by the host: invalidate the overlay). */
    var onChanged: () -> Unit = {}

    /** A snap finished with the pen up: commit its object (set by the host). */
    var onFinished: (PendingSnap) -> Unit = {}

    /** True from the snap until the pen lifts. */
    val snapped: Boolean get() = active && (shape != null || straight)

    /** Stack page of the [preview]; -1 while nothing snapped. */
    val previewPage: Int get() = if (snapped) page else -1

    /** True while the overlay has a snap to draw (live or pending). */
    val showing: Boolean get() = previewPage >= 0 || pending != null

    /** The finished snap while its tiles are not drawn yet. */
    var pending: PendingSnap? = null
        private set

    /** Snaps finished since the view was created (debug `state`). */
    var finishedCount = 0
        private set

    /**
     * A stroke with [brush] starts on page [pageId] (stack [pageIndex], origin at view px ([pageX], [pageY]) at
     * [viewScale]); [straightOnly]: a straight highlighter from the start.
     */
    @Suppress("LongParameterList") // the page placement of the stroke in view px plus its brush
    fun begin(
        pageId: PageId,
        pageIndex: Int,
        brush: BrushSpec,
        pageX: Float,
        pageY: Float,
        viewScale: Float,
        straightOnly: Boolean,
    ) {
        stop()
        active = true
        spec = brush
        this.pageId = pageId
        page = pageIndex
        originX = pageX
        originY = pageY
        scale = viewScale
        count = 0
        straight = straightOnly && brush.kind == BrushKind.HIGHLIGHTER
        if (!straight) timer.post(check, DwellDetector.DWELL_MS)
    }

    /** Adds the samples of [event] (historical first) for pointer [pointerIndex]. */
    fun track(
        event: MotionEvent,
        pointerIndex: Int,
    ) {
        // HOT PATH: per stylus MotionEvent; the sample arrays grow by doubling only.
        if (!active) return
        for (h in 0 until event.historySize) {
            add(event.getHistoricalX(pointerIndex, h), event.getHistoricalY(pointerIndex, h), event.getHistoricalEventTime(h))
        }
        add(event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)
        if (snapped) onChanged()
    }

    private fun add(
        xPx: Float,
        yPx: Float,
        timeMs: Long,
    ) {
        val x = (xPx - originX) / scale
        val y = (yPx - originY) / scale
        lastMs = timeMs
        toX = x
        toY = y
        if (snapped && count > 0) return
        if (count == xs.size) {
            xs = xs.copyOf(count * 2)
            ys = ys.copyOf(count * 2)
        }
        xs[count] = x
        ys[count] = y
        if (count == 0) {
            startMs = timeMs
            fromX = x
            fromY = y
            dwell.start(xPx, yPx, timeMs, 0)
        } else {
            dwell.move(xPx, yPx, timeMs, count)
        }
        count++
    }

    private fun onTimer() {
        if (!active || snapped) return
        val left = dwell.remainingMs(timer.nowMs())
        when {
            left == Long.MAX_VALUE -> timer.post(check, DwellDetector.DWELL_MS)
            left > 0L -> timer.post(check, left)
            else -> hold()
        }
    }

    /** The pen held still: snaps the stroke up to the hold if it fits; true if it did. */
    fun hold(): Boolean {
        dwell.consume()
        val end = dwell.anchorIndex
        val brush = spec?.takeIf { active && !snapped && end >= 0 } ?: return false
        val minSizePt = minShapePx / scale
        fromX = xs[end]
        fromY = ys[end]
        if (brush.kind == BrushKind.HIGHLIGHTER) {
            straight = hypot(fromX - xs[0], fromY - ys[0]) >= minSizePt
        } else {
            shape = ShapeRecognizer.recognize(xs, ys, end + 1, minSizePt)
        }
        if (snapped) {
            timer.cancel(check)
            onSnapped()
            onChanged()
        } else {
            timer.post(check, DwellDetector.DWELL_MS) // no fit: keep inking, a later hold may fit
        }
        return snapped
    }

    /** What the snap looks like now (page pt of [previewPage]), or null; the preview asks once per frame. */
    fun preview(): PageObject? = if (snapped) build(DRAFT_ID) else null

    /** The pen lifted: commits a snap through [onFinished] (it stays [pending]); without a snap just stops. */
    fun finish() {
        val pageId = pageId
        val obj = if (snapped) build(newId()) else null
        stop()
        if (obj == null || pageId == null) return
        val done = PendingSnap(pageId, page, obj)
        pending = done
        finishedCount++
        onChanged()
        onFinished(done)
    }

    /** Drops the stroke (pen canceled, or lifted without a snap). */
    fun stop() {
        val wasSnapped = snapped
        timer.cancel(check)
        dwell.stop()
        active = false
        shape = null
        straight = false
        if (wasSnapped) onChanged()
    }

    /** The pending snap's tiles are drawn (or its commit failed): the preview stops drawing it. */
    fun committed() {
        if (pending == null) return
        pending = null
        onChanged()
    }

    private fun build(id: ObjectId): PageObject? {
        val brush = spec ?: return null
        if (straight) {
            val inputs = StraightStroke.inputs(xs[0], ys[0], toX, toY, (lastMs - startMs).toFloat())
            return InkStroke.of(id, brush, inputs)
        }
        val fit = shape?.followed(PointPt(fromX, fromY), PointPt(toX, toY)) ?: return null
        val style = StrokeStyle(brush.argb, brush.sizePt, dashed = false)
        return ShapeGeometry.withPoints(ShapeGeometry.create(id, fit.kind, fit.points, style, null), fit.points, fit.rotationDeg)
    }

    private companion object {
        const val INITIAL_SAMPLES = 1024
        val DRAFT_ID = ObjectId("snap-draft")
    }
}
