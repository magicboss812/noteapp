package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.lasso.SelectionBox
import dev.folio.core.model.lasso.SelectionHandle
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the lasso over the content tiles (06-ink-input.md#lasso): the dashed path while it is drawn, the selection's box
 * (dashed accent), its eight handles and rotate knob, the angle while it turns, the moving selection (its snapshot under
 * the drag's transform, until the tiles show the result) and the action bar. Box chrome keeps its screen size at every
 * zoom; nothing here reaches tiles or export.
 */
internal class LassoOverlay(
    context: Context,
    private val viewport: Viewport,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val quad = FloatArray(QUAD_COORDS)
    private val toView = Matrix()
    private val affineMatrix = Matrix()
    private val matrixValues = FloatArray(MATRIX_VALUES)
    private val knobRadiusPx = KNOB_DP * density
    private val pathPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = PATH_DP * density
            color = PATH_ARGB
            pathEffect = DashPathEffect(floatArrayOf(DASH_DP * density, DASH_DP * density), 0f)
        }
    private val boxPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_DP * density
            color = ACCENT_ARGB
            pathEffect = DashPathEffect(floatArrayOf(DASH_DP * density, DASH_DP * density), 0f)
        }
    private val knobOutline =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_DP * density
            color = ACCENT_ARGB
        }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KNOB_FILL_ARGB }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_ARGB
            textAlign = Paint.Align.CENTER
            textSize = LABEL_DP * density
            typeface = Typeface.DEFAULT_BOLD
        }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // The angle label, made again only when the rounded angle changes.
    private var angleShown = Int.MIN_VALUE
    private var angleLabel = ""

    /** Where the lasso comes from; set once by the host. */
    var input: LassoInput? = null

    /** How far up from the window bottom the chrome covers the canvas (keyboard, bottom-docked toolbox), px. */
    var coveredBottomPx = 0
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }
    private val location = IntArray(2)

    /** The selection box the last frame drew in page pt (tests read it); null without one. */
    var boxShown: RectPt? = null
        private set

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        // HOT PATH: per frame while the lasso shows; no allocation.
        val source = input ?: return
        drawPath(canvas, source)
        drawSelection(canvas, source)
        drawToolbar(canvas, source)
    }

    private fun drawPath(
        canvas: Canvas,
        source: LassoInput,
    ) {
        val page = source.pathPage
        val n = source.pathSize
        if (page < 0 || n == 0) return
        val x0 = viewport.pageOriginViewX(page).toFloat()
        val y0 = viewport.pageOriginViewY(page).toFloat()
        val s = viewport.scale
        if (source.rectangle) {
            val ax = x0 + source.pathX(0) * s
            val ay = y0 + source.pathY(0) * s
            val bx = x0 + source.pathX(n - 1) * s
            val by = y0 + source.pathY(n - 1) * s
            canvas.drawRect(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by), pathPaint)
            return
        }
        path.rewind()
        path.moveTo(x0 + source.pathX(0) * s, y0 + source.pathY(0) * s)
        for (i in 1 until n) path.lineTo(x0 + source.pathX(i) * s, y0 + source.pathY(i) * s)
        canvas.drawPath(path, pathPaint)
    }

    private fun drawSelection(
        canvas: Canvas,
        source: LassoInput,
    ) {
        val page = source.selectionPage
        val box = if (page >= 0) source.box() else null
        boxShown = box
        if (box == null) return
        val x0 = viewport.pageOriginViewX(page).toFloat()
        val y0 = viewport.pageOriginViewY(page).toFloat()
        val s = viewport.scale
        val affine = source.liveAffine()
        source.snapshot?.let { snap ->
            if (affine != null) {
                toView.setScale(1f / snap.scale, 1f / snap.scale)
                toView.postTranslate(snap.regionPt.left, snap.regionPt.top)
                toView.postConcat(affine.toMatrix())
                toView.postScale(s, s)
                toView.postTranslate(x0, y0)
                canvas.drawBitmap(snap.bitmap, toView, bitmapPaint)
            }
        }
        corners(box, affine ?: Affine.IDENTITY, x0, y0, s)
        path.rewind()
        path.moveTo(quad[0], quad[1])
        for (i in 1 until QUAD_COORDS / 2) path.lineTo(quad[2 * i], quad[2 * i + 1])
        path.close()
        canvas.drawPath(path, boxPaint)
        if (affine == null) drawHandles(canvas, source, box, x0, y0, s)
        source.rotationDeg()?.let { deg -> drawAngle(canvas, deg, box, x0, y0, s) }
    }

    // The four corners of [box] mapped by [affine], in view px, into [quad].
    private fun corners(
        box: RectPt,
        affine: Affine,
        x0: Float,
        y0: Float,
        s: Float,
    ) {
        // Clockwise from the top left.
        for (i in 0 until QUAD_COORDS / 2) {
            val x = if (i == 1 || i == 2) box.right else box.left
            val y = if (i >= 2) box.bottom else box.top
            setCorner(i, affine, x, y, x0, y0, s)
        }
    }

    private fun setCorner(
        i: Int,
        affine: Affine,
        x: Float,
        y: Float,
        x0: Float,
        y0: Float,
        s: Float,
    ) {
        quad[2 * i] = x0 + affine.mapX(x, y) * s
        quad[2 * i + 1] = y0 + affine.mapY(x, y) * s
    }

    private fun drawHandles(
        canvas: Canvas,
        source: LassoInput,
        box: RectPt,
        x0: Float,
        y0: Float,
        s: Float,
    ) {
        val offset = source.rotateOffsetPt()
        val topX = x0 + (box.left + box.right) / 2f * s
        canvas.drawLine(topX, y0 + box.top * s, topX, y0 + (box.top - offset) * s, knobOutline)
        for (h in SelectionHandle.entries) {
            val p = SelectionBox.position(box, h, offset)
            val x = x0 + p.x * s
            val y = y0 + p.y * s
            canvas.drawCircle(x, y, knobRadiusPx, knobFill)
            canvas.drawCircle(x, y, knobRadiusPx, knobOutline)
        }
    }

    private fun drawAngle(
        canvas: Canvas,
        deg: Float,
        box: RectPt,
        x0: Float,
        y0: Float,
        s: Float,
    ) {
        val rounded = deg.roundToInt()
        if (rounded != angleShown) {
            angleShown = rounded
            angleLabel = "$rounded°"
        }
        val c = box.center
        canvas.drawText(angleLabel, x0 + c.x * s, y0 + c.y * s - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
    }

    private fun drawToolbar(
        canvas: Canvas,
        source: LassoInput,
    ) {
        val toolbar = source.toolbar
        val paste = source.pasteAt
        val box = boxShown
        val page = source.selectionPage
        // The bar stays clear of the chrome covering the canvas bottom (a bottom-docked toolbox, the keyboard).
        getLocationInWindow(location)
        val belowPx = rootView.height - (location[1] + height)
        val clearHeight = (height - max(0, coveredBottomPx - belowPx)).toFloat()
        when {
            box != null && page >= 0 && source.liveAffine() == null -> {
                val x0 = viewport.pageOriginViewX(page).toFloat()
                val y0 = viewport.pageOriginViewY(page).toFloat()
                val s = viewport.scale
                val top = y0 + (box.top - source.rotateOffsetPt()) * s - knobRadiusPx
                toolbar.place(x0 + box.center.x * s, top, y0 + box.bottom * s + knobRadiusPx, width.toFloat(), clearHeight)
            }

            paste != null && box == null -> {
                val index = source.pageIndexOf(paste.first)
                if (index < 0) return toolbar.clear()
                val x = viewport.pageOriginViewX(index).toFloat() + paste.second.x * viewport.scale
                val y = viewport.pageOriginViewY(index).toFloat() + paste.second.y * viewport.scale
                toolbar.place(x, y, y, width.toFloat(), clearHeight, paste = true)
            }

            else -> {
                toolbar.clear()
                return
            }
        }
        toolbar.draw(canvas)
    }

    private fun Affine.toMatrix(): Matrix {
        matrixValues[Matrix.MSCALE_X] = a
        matrixValues[Matrix.MSKEW_X] = c
        matrixValues[Matrix.MTRANS_X] = tx
        matrixValues[Matrix.MSKEW_Y] = b
        matrixValues[Matrix.MSCALE_Y] = d
        matrixValues[Matrix.MTRANS_Y] = ty
        matrixValues[Matrix.MPERSP_0] = 0f
        matrixValues[Matrix.MPERSP_1] = 0f
        matrixValues[Matrix.MPERSP_2] = 1f
        affineMatrix.setValues(matrixValues)
        return affineMatrix
    }

    private companion object {
        const val QUAD_COORDS = 8
        const val MATRIX_VALUES = 9
        const val PATH_DP = 1.5f
        const val DASH_DP = 5f
        const val LABEL_DP = 15f
        const val PATH_ARGB = 0xFF5F6368.toInt()

        // Selection chrome like the shape and text box handles (ShapePreview, BoxHandles).
        const val KNOB_DP = 5f
        const val OUTLINE_DP = 1.5f
        const val ACCENT_ARGB = 0xFF4B85E0.toInt()
        const val KNOB_FILL_ARGB = 0xFFFDFDFE.toInt()
    }
}
