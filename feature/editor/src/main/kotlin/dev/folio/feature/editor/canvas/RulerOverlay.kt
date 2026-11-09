package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.View
import dev.folio.core.ink.ruler.Ruler
import dev.folio.core.ink.ruler.RulerAngles
import kotlin.math.max

/**
 * The ruler (06-ink-input.md#ruler): a translucent 18 cm straight edge on the glass with mm and cm ticks and an angle
 * readout, the same physical size at every zoom; it stays put while the pages pan and zoom under it. [RulerGestures]
 * moves and turns it, [PenInput] makes strokes starting near an edge follow it. Shown per pane while the toolbar's Ruler
 * toggle is on; nothing is stored (A-069).
 */
internal class RulerOverlay(
    context: Context,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val mmPx = resources.displayMetrics.xdpi / MM_PER_INCH

    /** The ruler's pose in view px; read through [active]. */
    val ruler = Ruler((LENGTH_MM + 2 * END_MM) * mmPx, WIDTH_DP * density)

    /** True while the toggle shows the ruler. */
    var shown = false
        private set
    private var placed = false

    /** The ruler while it shows, else null (the pen reads it at every stylus down). */
    val active: Ruler? get() = if (shown && placed) ruler else null

    private val body = RectF()
    private val readout = RectF()
    private val cornerPx = CORNER_DP * density
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BODY_ARGB }
    private val edgePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = EDGE_DP * density
            color = TICK_ARGB
        }
    private val tickPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = TICK_DP * density
            color = TICK_ARGB
        }
    private val numberPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TICK_ARGB
            textAlign = Paint.Align.CENTER
            textSize = NUMBER_DP * density
        }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val readoutText =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = READOUT_TEXT_ARGB
            textAlign = Paint.Align.CENTER
            textSize = READOUT_DP * density
            typeface = Typeface.DEFAULT_BOLD
        }

    // Both edges' ticks in ruler coordinates (center at 0, 0), drawn with one drawLines call.
    private val ticks = tickLines()

    // The readout, made again only when the shown angle changes.
    private var readoutDeg = Int.MIN_VALUE
    private var readoutLabel = ""

    init {
        setWillNotDraw(false)
    }

    /** Shows or hides the ruler; the first show puts it level in the middle of the view. */
    fun show(on: Boolean) {
        if (on == shown) return
        shown = on
        if (on && !placed && width > 0) place(width / 2f, height / 2f, 0f)
        invalidate()
    }

    /** Moves the ruler's center to ([xPx], [yPx]) (kept inside the view) turned by [deg]. */
    fun place(
        xPx: Float,
        yPx: Float,
        deg: Float,
    ) {
        val w = max(width, 1).toFloat()
        val h = max(height, 1).toFloat()
        ruler.set(xPx.coerceIn(0f, w), yPx.coerceIn(0f, h), deg)
        placed = true
        invalidate()
    }

    /** The readout's tap target: its pill grown to [minPx] on each side. */
    fun readoutCovers(
        xPx: Float,
        yPx: Float,
        minPx: Float,
    ): Boolean {
        val r = readoutRect()
        val dx = max(0f, (minPx - r.width()) / 2f)
        val dy = max(0f, (minPx - r.height()) / 2f)
        return xPx >= r.left - dx && xPx <= r.right + dx && yPx >= r.top - dy && yPx <= r.bottom + dy
    }

    /** A light tick as the turn rests in a detent. */
    fun detentFeedback() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (placed) {
            place(ruler.centerXPx, ruler.centerYPx, ruler.angleDeg)
        } else if (shown) {
            place(w / 2f, h / 2f, 0f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // HOT PATH: per frame while the ruler shows or moves; no allocation.
        if (!shown || !placed) return
        val halfL = ruler.lengthPx / 2f
        val halfW = ruler.widthPx / 2f
        canvas.save()
        canvas.translate(ruler.centerXPx, ruler.centerYPx)
        canvas.rotate(ruler.angleDeg)
        body.set(-halfL, -halfW, halfL, halfW)
        canvas.drawRoundRect(body, cornerPx, cornerPx, bodyPaint)
        canvas.drawRoundRect(body, cornerPx, cornerPx, edgePaint)
        canvas.drawLines(ticks, tickPaint)
        val numberY = -halfW + (CM_TICK_DP + NUMBER_GAP_DP) * density + numberPaint.textSize
        for (cm in 1 until LENGTH_MM / MM_PER_CM) {
            canvas.drawText(CM_LABELS[cm], -LENGTH_MM / 2f * mmPx + cm * MM_PER_CM * mmPx, numberY, numberPaint)
        }
        canvas.restore()
        drawReadout(canvas)
    }

    // The angle in a pill below the ruler's middle, always upright; accent while the turn rests in a detent.
    private fun drawReadout(canvas: Canvas) {
        val r = readoutRect()
        readoutPaint.color = if (RulerAngles.isDetent(ruler.angleDeg)) ACCENT_ARGB else READOUT_ARGB
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, readoutPaint)
        val baseline = r.centerY() - (readoutText.descent() + readoutText.ascent()) / 2f
        canvas.drawText(readoutLabel, r.centerX(), baseline, readoutText)
    }

    private fun readoutRect(): RectF {
        val deg = RulerAngles.readoutDeg(ruler.angleDeg)
        if (deg != readoutDeg) {
            readoutDeg = deg
            readoutLabel = "$deg°"
        }
        val halfW = readoutText.measureText(readoutLabel) / 2f + READOUT_PAD_DP * density
        val halfH = READOUT_HEIGHT_DP * density / 2f
        // In the lower half, clear of the cm numbers along the top edge and the ticks of the bottom one.
        val across = ruler.widthPx / 2f - (CM_TICK_DP + NUMBER_GAP_DP) * density - halfH
        val x = ruler.centerXPx - across * ruler.dirY
        val y = ruler.centerYPx + across * ruler.dirX
        readout.set(x - halfW, y - halfH, x + halfW, y + halfH)
        return readout
    }

    private fun tickLines(): FloatArray {
        val count = LENGTH_MM + 1
        val out = FloatArray(count * LINES_PER_TICK * COORDS_PER_LINE)
        val halfW = ruler.widthPx / 2f
        var k = 0
        for (mm in 0..LENGTH_MM) {
            val x = (mm - LENGTH_MM / 2f) * mmPx
            val len =
                density *
                    when {
                        mm % MM_PER_CM == 0 -> CM_TICK_DP
                        mm % HALF_CM == 0 -> HALF_TICK_DP
                        else -> MM_TICK_DP
                    }
            for (side in intArrayOf(-1, 1)) {
                out[k++] = x
                out[k++] = side * halfW
                out[k++] = x
                out[k++] = side * (halfW - len)
            }
        }
        return out
    }

    private companion object {
        const val MM_PER_INCH = 25.4f
        const val LENGTH_MM = 180
        const val END_MM = 4
        const val MM_PER_CM = 10
        const val HALF_CM = 5
        const val WIDTH_DP = 80f
        const val CORNER_DP = 6f
        const val EDGE_DP = 1f
        const val TICK_DP = 1f
        const val MM_TICK_DP = 7f
        const val HALF_TICK_DP = 11f
        const val CM_TICK_DP = 16f
        const val NUMBER_DP = 11f
        const val NUMBER_GAP_DP = 2f
        const val READOUT_DP = 14f
        const val READOUT_PAD_DP = 10f
        const val READOUT_HEIGHT_DP = 26f
        const val LINES_PER_TICK = 2
        const val COORDS_PER_LINE = 4
        const val BODY_ARGB = 0xB8EEF1F6.toInt()
        const val TICK_ARGB = 0xFF5F6368.toInt()
        const val READOUT_ARGB = 0xFF5F6368.toInt()
        const val ACCENT_ARGB = 0xFF4B85E0.toInt()
        const val READOUT_TEXT_ARGB = 0xFFFFFFFF.toInt()
        val CM_LABELS = Array(LENGTH_MM / MM_PER_CM + 1) { it.toString() }
    }
}
