package dev.folio.app.spikes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.core.graphics.withTranslation
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.layout.BaselineMethod
import dev.folio.core.text.layout.GridTextLayouter
import dev.folio.core.text.spike.FontSpecimen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spike P01-S3: every bundled font on college-ruled paper (U = 7.1 mm, body M), laid out with the
 * selected [BaselineMethod]. Finger drag pans; zoom and method come from `debugcmd.sh spike-fonts`.
 * Kept for the P01-S3 USER-CHECK; removed by P06-T03 (STATUS D-002).
 */
@SuppressLint("ViewConstructor") // created in code by AppDebugHooks only
@MainThread
internal class SpikeFontsView(
    context: Context,
    private val dispatchers: FolioDispatchers,
) : View(context) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val resolver = createFontFamilyResolver(context)
    private val layouter = GridTextLayouter(resolver, FontMetricsCache(resolver))
    private val sidePaddingPx = SIDE_PADDING_DP * resources.displayMetrics.density
    private var specimen: FontSpecimen? = null
    private var build: Job? = null
    private var offsetXPx = 0f
    private var offsetYPx = 0f
    private var lastX = 0f
    private var lastY = 0f

    var method = BaselineMethod.GRID_PITCH
        private set

    /** Zoom as a multiple of fit-width. */
    var zoom = 1f
        private set

    /** Time to lay out all specimen blocks, null while building. */
    var layoutMs: Double? = null
        private set

    /** px per pt at the current zoom. */
    val scale: Float get() = (width - 2 * sidePaddingPx) / FontSpecimen.WIDTH_PT * zoom

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rebuild()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    /** Lays out the specimen again with [next]. */
    fun switchMethod(next: BaselineMethod) {
        method = next
        rebuild()
    }

    /** Sets [target] zoom keeping the view center fixed. */
    fun applyZoom(target: Float) {
        val before = scale
        val focusX = width / 2f
        val focusY = height / 2f
        zoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = if (before == 0f) 1f else scale / before
        offsetXPx = focusX - (focusX - offsetXPx) * k
        offsetYPx = focusY - (focusY - offsetYPx) * k
        clamp()
        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) = clamp()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(SURROUND_ARGB)
        val page = specimen ?: return
        canvas.withTranslation(offsetXPx, offsetYPx) { page.draw(this, scale) }
    }

    @SuppressLint("ClickableViewAccessibility") // spike: drag to pan only, no click action
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Unit
            }

            MotionEvent.ACTION_MOVE -> {
                offsetXPx += event.x - lastX
                offsetYPx += event.y - lastY
                clamp()
                invalidate()
            }
        }
        lastX = event.x
        lastY = event.y
        return true
    }

    private fun rebuild() {
        build?.cancel()
        layoutMs = null
        val next = method
        build =
            scope.launch {
                val startNs = SystemClock.elapsedRealtimeNanos()
                specimen = withContext(dispatchers.text) { FontSpecimen(layouter, FontRegistry.families, next, compact = false) }
                layoutMs = (SystemClock.elapsedRealtimeNanos() - startNs) / NS_PER_MS
                clamp()
                invalidate()
            }
    }

    /** Centers a page narrower than the view; otherwise keeps at least half a screen of page visible. */
    private fun clamp() {
        val contentW = FontSpecimen.WIDTH_PT * scale
        val contentH = (specimen?.heightPt ?: 0f) * scale
        offsetXPx = if (contentW <= width) (width - contentW) / 2f else offsetXPx.coerceIn(width / 2f - contentW, width / 2f)
        offsetYPx = offsetYPx.coerceIn(minOf(0f, height / 2f - contentH), height / 2f)
    }

    companion object {
        const val ROUTE = "spike-fonts"
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 8f
        private const val SIDE_PADDING_DP = 24f
        private const val NS_PER_MS = 1_000_000.0
        private const val SURROUND_ARGB = 0xFFE9ECF1.toInt()
    }
}
