package dev.folio.app.debug

import android.os.Handler
import android.os.Looper
import android.util.Printer
import android.view.FrameMetrics
import android.view.Window
import dev.folio.core.common.FolioLog

/**
 * Logs main thread messages and frames longer than [LONG_MS] (tag FolioMain): the message's handler and callback,
 * and a frame's input, animation, layout, draw and sync parts, to find what delays the live text field after a tap
 * (P06-T18). Debug builds only.
 */
internal class MainThreadProbe private constructor() :
    Printer,
    Window.OnFrameMetricsAvailableListener {
        private var startNs = 0L
        private var dispatching: String? = null

        override fun println(x: String) {
            if (x.startsWith(">")) {
                startNs = System.nanoTime()
                dispatching = x
                return
            }
            val name = dispatching ?: return
            dispatching = null
            val durationMs = (System.nanoTime() - startNs) / NS_PER_MS
            if (durationMs >= LONG_MS) FolioLog.i(TAG, "msg ${durationMs}ms ${name.take(MAX_NAME)}")
        }

        override fun onFrameMetricsAvailable(
            window: Window,
            metrics: FrameMetrics,
            dropCount: Int,
        ) {
            val totalMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / NS_PER_MS
            if (totalMs < LONG_MS) return
            FolioLog.i(
                TAG,
                "frame ${totalMs}ms delay=${ms(metrics, FrameMetrics.UNKNOWN_DELAY_DURATION)} " +
                    "input=${ms(metrics, FrameMetrics.INPUT_HANDLING_DURATION)} " +
                    "anim=${ms(metrics, FrameMetrics.ANIMATION_DURATION)} " +
                    "layout=${ms(metrics, FrameMetrics.LAYOUT_MEASURE_DURATION)} " +
                    "draw=${ms(metrics, FrameMetrics.DRAW_DURATION)} " +
                    "sync=${ms(metrics, FrameMetrics.SYNC_DURATION)} " +
                    "gpu=${ms(metrics, FrameMetrics.COMMAND_ISSUE_DURATION)}",
            )
        }

        private fun ms(
            metrics: FrameMetrics,
            id: Int,
        ): Long = metrics.getMetric(id) / NS_PER_MS

        internal companion object {
            const val TAG = "FolioMain"
            private const val LONG_MS = 8L
            private const val NS_PER_MS = 1_000_000L
            private const val MAX_NAME = 160

            /** Starts logging the main looper and [window]'s frames. */
            fun install(window: Window) {
                val probe = MainThreadProbe()
                Looper.getMainLooper().setMessageLogging(probe)
                window.addOnFrameMetricsAvailableListener(probe, Handler(Looper.getMainLooper()))
            }
        }
    }
