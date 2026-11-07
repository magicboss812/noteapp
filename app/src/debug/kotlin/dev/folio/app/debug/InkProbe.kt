package dev.folio.app.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import androidx.annotation.MainThread
import dev.folio.core.common.FolioLog
import dev.folio.feature.editor.canvas.CanvasHostView

/**
 * `ink-probe y[,ms[,band]]`: copies the window's row band ([bandPx] tall, default 16) at view y [y] px every frame for [ms] (default 3000) and
 * logs (tag FolioDebug) how many saturated ink pixels each frame showed and when wet strokes were removed.
 * The copy holds the HWUI window only, not androidx.ink's front buffer layers, so a frame with less ink than
 * the one before (a gap) is a frame where neither ink's finished-stroke view nor the content tiles drew a stroke.
 */
@MainThread
internal class InkProbe(
    private val host: CanvasHostView,
    private val y: Int,
    private val durationMs: Long,
    private val bandPx: Int,
) : Choreographer.FrameCallback {
    private val band = Bitmap.createBitmap(host.width, bandPx, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(band.width * band.height)
    private val samples = ArrayList<String>()
    private val startMs = SystemClock.uptimeMillis()
    private val handler = Handler(Looper.getMainLooper())
    private var inFlight = false
    private var removed = host.handoffStats.removed
    private var seenInk = 0
    private var gaps = 0

    fun start() = Choreographer.getInstance().postFrameCallback(this)

    override fun doFrame(frameTimeNanos: Long) {
        val t = SystemClock.uptimeMillis() - startMs
        val nowRemoved = host.handoffStats.removed
        if (nowRemoved != removed) {
            samples += "$t:removed"
            removed = nowRemoved
            removedAtMs = t
        }
        // One line per stroke, a second after its removal, so long runs are readable while they go on.
        if (removedAtMs >= 0 && t - removedAtMs > FLUSH_AFTER_MS) {
            FolioLog.i(DebugReply.TAG, "ink-probe y=$y max=$seenInk gaps=$gaps ${samples.joinToString(" ")}")
            samples.clear()
            removedAtMs = -1
        }
        if (t > durationMs) {
            FolioLog.i(DebugReply.TAG, "ink-probe y=$y max=$seenInk gaps=$gaps done ${samples.joinToString(" ")}")
            return
        }
        if (!inFlight) {
            inFlight = true
            val loc = IntArray(2).also(host::getLocationInWindow)
            val src = Rect(loc[0], loc[1] + y - bandPx / 2, loc[0] + band.width, loc[1] + y + bandPx / 2)
            PixelCopy.request(
                PixelCopy.Request.Builder
                    .ofWindow(host)
                    .setSourceRect(src)
                    .setDestinationBitmap(band)
                    .build(),
                handler::post,
            ) {
                inFlight = false
                if (it.status == PixelCopy.SUCCESS) record(SystemClock.uptimeMillis() - startMs)
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun record(t: Long) {
        band.getPixels(pixels, 0, band.width, 0, 0, band.width, band.height)
        var ink = 0
        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            if (maxOf(r, g, b) - minOf(r, g, b) > SATURATION) ink++
        }
        // Strokes only arrive in the window, so any drop by more than a tenth is a frame missing a stroke.
        if (previous > 0 && (previous - ink) * CHANGE_PARTS > previous) gaps++
        previous = ink
        if (ink > seenInk) seenInk = ink
        // Only changes by more than a tenth are logged (logcat cuts long lines).
        if (kotlin.math.abs(ink - lastLogged) * CHANGE_PARTS > maxOf(lastLogged, 1)) {
            samples += "$t:$ink"
            lastLogged = ink
        }
    }

    private var lastLogged = -1
    private var previous = 0
    private var removedAtMs = -1L

    private companion object {
        const val SATURATION = 60
        const val CHANGE_PARTS = 10
        const val FLUSH_AFTER_MS = 1000L
    }
}
