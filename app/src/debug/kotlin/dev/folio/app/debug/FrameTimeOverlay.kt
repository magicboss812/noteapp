package dev.folio.app.debug

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.MainThread
import java.util.Locale

/**
 * Debug-only frame-time readout (top end corner): rendered frames per second, average and max
 * total frame duration, and frames over the 144 Hz budget, from Window frame metrics.
 */
@MainThread
internal class FrameTimeOverlay(
    private val activity: Activity,
) : Window.OnFrameMetricsAvailableListener {
    private val handler = Handler(Looper.getMainLooper())
    private val label =
        TextView(activity).apply {
            setBackgroundColor(BACKGROUND)
            setTextColor(Color.WHITE)
            textSize = TEXT_SP
            setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
            visibility = View.GONE
            // Edge-to-edge: keep the readout below the status bar and clear of the display cutout.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.translationY = bars.top.toFloat()
                view.translationX = -bars.right.toFloat()
                insets
            }
        }
    private var frames = 0
    private var sumNs = 0L
    private var maxNs = 0L
    private var overBudget = 0
    private var showing = false

    private val tick =
        object : Runnable {
            override fun run() {
                label.text = summary()
                frames = 0
                sumNs = 0L
                maxNs = 0L
                overBudget = 0
                handler.postDelayed(this, WINDOW_MS)
            }
        }

    fun install() {
        val params =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            )
        activity.addContentView(label, params)
    }

    fun setVisible(visible: Boolean) {
        if (visible == showing) return
        showing = visible
        if (visible) {
            activity.window.addOnFrameMetricsAvailableListener(this, handler)
            label.visibility = View.VISIBLE
            handler.postDelayed(tick, WINDOW_MS)
        } else {
            handler.removeCallbacks(tick)
            activity.window.removeOnFrameMetricsAvailableListener(this)
            label.visibility = View.GONE
        }
    }

    /** Stops the readout loop and frame listener; call when the activity is destroyed. */
    fun dispose() = setVisible(false)

    override fun onFrameMetricsAvailable(
        window: Window,
        frameMetrics: FrameMetrics,
        dropCountSinceLastInvocation: Int,
    ) {
        val durationNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
        frames++
        sumNs += durationNs
        if (durationNs > maxNs) maxNs = durationNs
        if (durationNs > BUDGET_NS) overBudget++
    }

    private fun summary(): String {
        val fps = frames * MS_PER_S / WINDOW_MS
        val avgMs = if (frames == 0) 0.0 else sumNs / frames / NS_PER_MS
        return String.format(Locale.US, "%d fps  avg %.1f ms  max %.1f ms  >6.9ms %d", fps, avgMs, maxNs / NS_PER_MS, overBudget)
    }

    private companion object {
        const val WINDOW_MS = 1000L
        const val MS_PER_S = 1000L
        const val NS_PER_MS = 1_000_000.0
        const val BUDGET_NS = 6_944_444L // one frame at 144 Hz
        const val TEXT_SP = 12f
        const val PADDING_PX = 12
        const val BACKGROUND = 0xB0000000.toInt()
    }
}
