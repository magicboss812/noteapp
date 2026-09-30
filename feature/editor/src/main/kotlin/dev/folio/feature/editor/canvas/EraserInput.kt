package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.common.PerfMonitor
import dev.folio.core.ink.erase.EraseResult
import dev.folio.core.ink.erase.EraseSession
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.ink.input.StylusTarget
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Eraser counters for debug `state`, since the canvas view was created. */
data class EraseStats(
    /** Eraser gestures started. */
    val gestures: Int,
    /** Gestures handed to the session (they touched something). */
    val committed: Int,
    /** Original strokes erased or split by committed gestures. */
    val strokes: Int,
    /** Gestures canceled, or rejected by the session. */
    val discarded: Int,
)

/**
 * The eraser on the stylus route (06-ink-input.md#erasers). A stylus down starts an [EraseSession] on a
 * snapshot of the page under the pen; the samples (page pt) queue up for a [worker] coroutine that erases
 * and hands every changed [EraseResult] to [onPreview] on the main thread. Lifting the pen hands the final
 * result to [onFinished] (the host commits it as one command); a cancel previews null again.
 */
@MainThread
internal class EraserInput(
    private val viewport: Viewport,
    private val body: (PageId) -> Page?,
    private val options: () -> EraserOptions,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val onPreview: (EraseResult?) -> Unit,
    private val onFinished: (EraseResult) -> Unit,
) : StylusTarget {
    private var queue: PointQueue? = null
    private var wakeups: Channel<Unit>? = null
    private var job: Job? = null
    private var originX = 0f
    private var originY = 0f
    private var scale = 1f
    private var started = 0
    private var canceled = 0

    /** Gestures started. */
    val startedCount: Int get() = started

    /** Gestures canceled. */
    val canceledCount: Int get() = canceled

    override fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    ) {
        val index = event.findPointerIndex(pointerId)
        val page = if (index < 0) -1 else viewport.pageIndexAt(event.getY(index))
        val pageId =
            viewport.layout
                ?.pages
                ?.getOrNull(page)
                ?.id ?: return
        val snapshot = body(pageId) ?: return // not decoded yet: nothing visible to erase
        scale = viewport.scale
        originX = viewport.pageOriginViewX(page).toFloat()
        originY = viewport.pageOriginViewY(page).toFloat()
        val points = PointQueue()
        val signal = Channel<Unit>(Channel.CONFLATED)
        queue = points
        wakeups = signal
        started++
        add(event, index)
        val settings = options()
        job = scope.launch { erase(snapshot, settings, points, signal) }
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent; samples go into a reused buffer, the worker erases.
        if (queue == null) return
        add(event, event.findPointerIndex(pointerId))
    }

    override fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    ) {
        if (queue == null) return
        add(event, event.findPointerIndex(pointerId))
        wakeups?.close()
        queue = null
        wakeups = null
        job = null
    }

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) {
        if (queue == null) return
        job?.cancel()
        queue = null
        wakeups = null
        job = null
        canceled++
        onPreview(null)
    }

    private fun add(
        event: MotionEvent,
        index: Int,
    ) {
        // HOT PATH: per MotionEvent.
        val points = queue ?: return
        if (index < 0) return
        for (h in 0 until event.historySize) {
            points.add((event.getHistoricalX(index, h) - originX) / scale, (event.getHistoricalY(index, h) - originY) / scale)
        }
        points.add((event.getX(index) - originX) / scale, (event.getY(index) - originY) / scale)
        wakeups?.trySend(Unit)
    }

    /** One gesture: erases on [worker] until the pen lifts (the channel closes), then finishes on main. */
    private suspend fun CoroutineScope.erase(
        page: Page,
        settings: EraserOptions,
        points: PointQueue,
        signal: Channel<Unit>,
    ) {
        val gesture = this
        val result =
            withContext(worker) {
                val session = EraseSession(page, settings)
                for (ignored in signal) {
                    val preview = PerfMonitor.trace(SECTION_ERASE) { if (points.drainInto(session)) session.result() else null }
                    if (preview != null) gesture.launch { onPreview(preview) }
                }
                PerfMonitor.trace(SECTION_ERASE) {
                    points.drainInto(session)
                    session.result()
                }
            }
        onFinished(result)
    }

    /** PerfMonitor sections. */
    companion object {
        /** Worker time per drained sample batch: erasing plus building the preview result. */
        const val SECTION_ERASE = "ink:erase"
    }
}

/**
 * Eraser samples from the main thread to the worker: two buffers swapped under a lock, so adding does
 * not allocate (except to grow) and the worker erases without holding the lock.
 */
private class PointQueue {
    private val lock = Any()
    private var write = FloatArray(INITIAL_FLOATS)
    private var writeCount = 0
    private var read = FloatArray(INITIAL_FLOATS)

    fun add(
        xPt: Float,
        yPt: Float,
    ) {
        synchronized(lock) {
            if (writeCount + 2 > write.size) write = write.copyOf(write.size * 2)
            write[writeCount++] = xPt
            write[writeCount++] = yPt
        }
    }

    /** Worker side: feeds every queued sample to [session]; true if anything was erased. */
    fun drainInto(session: EraseSession): Boolean {
        val count: Int
        synchronized(lock) {
            val swap = read
            read = write
            write = swap
            count = writeCount
            writeCount = 0
        }
        var changed = false
        for (i in 0 until count step 2) changed = session.moveTo(read[i], read[i + 1]) || changed
        return changed
    }

    private companion object {
        const val INITIAL_FLOATS = 512
    }
}
