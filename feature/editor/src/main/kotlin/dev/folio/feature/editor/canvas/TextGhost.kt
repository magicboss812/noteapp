package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Picture
import android.view.View
import androidx.annotation.MainThread
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY

/**
 * The last picture of a block that left the live field, drawn where the field was until the tiles draw the block
 * ([TextHandoff.leaving]); it moves with the viewport like the field.
 */
@MainThread
internal class TextGhost(
    context: Context,
) : View(context) {
    private val picture = Picture()
    private var pageIndex = -1
    private var offsetXPx = 0f
    private var offsetYPx = 0f
    private var capturedScale = 1f
    private var fieldScale = 1f

    init {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Records what [field] draws now (without the caret) on page [index] and shows it in the field's place. */
    fun capture(
        field: LiveTextField,
        viewport: Viewport,
        index: Int,
    ) {
        val w = field.width
        val h = field.height
        val sized = w > 0 && h > 0
        if (!sized || index < 0 || field.visibility != VISIBLE) {
            hide()
            return
        }
        val cursor = field.isCursorVisible
        field.isCursorVisible = false
        field.draw(picture.beginRecording(w, h))
        picture.endRecording()
        field.isCursorVisible = cursor
        pageIndex = index
        capturedScale = viewport.scale
        fieldScale = field.scaleX
        offsetXPx = (field.translationX - viewport.pageOriginViewX(index)).toFloat()
        offsetYPx = (field.translationY - viewport.pageOriginViewY(index)).toFloat()
        if (width != w || height != h) requestLayout()
        pivotX = 0f
        pivotY = 0f
        place(viewport)
        visibility = VISIBLE
        invalidate()
    }

    /** Moves the picture with the viewport. */
    fun place(viewport: Viewport) {
        // HOT PATH: per viewport change while the picture is shown; no allocation.
        if (visibility != VISIBLE || pageIndex < 0) return
        val k = viewport.scale / capturedScale
        translationX = (viewport.pageOriginViewX(pageIndex) + offsetXPx * k).toFloat()
        translationY = (viewport.pageOriginViewY(pageIndex) + offsetYPx * k).toFloat()
        scaleX = fieldScale * k
        scaleY = fieldScale * k
    }

    /** Removes the picture. */
    fun hide() {
        pageIndex = -1
        visibility = GONE
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) = setMeasuredDimension(picture.width.coerceAtLeast(0), picture.height.coerceAtLeast(0))

    override fun onDraw(canvas: Canvas) {
        if (pageIndex >= 0) canvas.drawPicture(picture)
    }
}
