package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.MainThread
import dev.folio.core.model.PageId
import dev.folio.core.render.OverlayTextPainter
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.core.text.flow.EditedBlock
import dev.folio.core.text.flow.TextRun

/**
 * The edited block on the canvas (A-058, 07-text-engine.md#live-field-as-built): its source text laid out and
 * paginated like the page text, drawn over the tiles that leave the block out, with a blinking caret. The live field
 * under it holds the focus and the input method but draws nothing. A block that just left editing stays drawn until
 * the tiles show it ([leave]).
 */
@MainThread
internal class EditedTextView(
    context: Context,
    private val viewport: Viewport,
    private val indexOf: (PageId) -> Int,
) : View(context) {
    private val painter = OverlayTextPainter()
    private val single = ArrayList<TextRun>(1)
    private val caretPaint = Paint().apply { color = CARET_ARGB }
    private val caretWidthPx = CARET_DP * resources.displayMetrics.density
    private var block: EditedBlock? = null
    private var leaving: EditedBlock? = null
    private var textArgb = 0
    private var textShown = false
    private var caret = NO_CARET
    private var caretOn = true
    private val blink =
        Runnable {
            caretOn = !caretOn
            invalidate()
            scheduleBlink()
        }

    init {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The edited block drawn, null for none (tests). */
    val shown: EditedBlock? get() = block

    /** The block that left editing and is still drawn, null for none (tests). */
    val left: EditedBlock? get() = leaving

    /** The caret offset drawn in the source, -1 while a range is selected or nothing is edited (tests). */
    val caretOffset: Int get() = caret

    /** True while the block's text is drawn (false while the tiles still show the block, P06-T18). */
    val drawsText: Boolean get() = textShown

    /** True in the visible half of the caret blink (debug automation). */
    val blinkOn: Boolean get() = caretOn

    /**
     * Draws [next] in [argb] with the caret at source offset [caretAt] (-1 for none, a range is selected); [withText]
     * false draws only the caret. A moved caret shows at once and blinks from there.
     */
    fun show(
        next: EditedBlock?,
        argb: Int,
        caretAt: Int,
        withText: Boolean,
    ) {
        val caretNow = if (next == null) NO_CARET else caretAt
        val same = next === block && argb == textArgb && (caretNow == caret && withText == textShown)
        if (same) return
        val restart = caretNow != caret || next?.blockId != block?.blockId
        block = next
        textArgb = argb
        caret = caretNow
        textShown = withText
        if (restart) {
            caretOn = true
            removeCallbacks(blink)
            if (caret != NO_CARET) scheduleBlink()
        } else if (caret == NO_CARET) {
            removeCallbacks(blink)
        }
        invalidate()
    }

    /** Keeps drawing [block] (no caret) after it left editing, until [settled]; null drops it. */
    fun leave(block: EditedBlock?) {
        if (block === leaving) return
        leaving = block
        invalidate()
    }

    /** The tiles show the block that left: it is no longer drawn here. */
    fun settled() = leave(null)

    /**
     * Puts the top and bottom of the line box that shows source offset [offset] of the drawn block into [out] (view
     * px, the page it is on); false when no block is drawn or that line is not placed.
     */
    fun linePx(
        offset: Int,
        out: FloatArray,
    ): Boolean {
        val spot = block?.caretSpot(offset) ?: return false
        val index = indexOf(spot.pageId)
        if (index < 0) return false
        val y = viewport.pageOriginViewY(index).toFloat()
        out[0] = y + spot.topPt * viewport.scale
        out[1] = y + spot.bottomPt * viewport.scale
        return true
    }

    private fun scheduleBlink() {
        postDelayed(blink, BLINK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // HOT PATH: per viewport change and keystroke while a block is edited; no allocation.
        leaving?.let { drawText(canvas, it) }
        val current = block ?: return
        if (textShown) drawText(canvas, current)
        if (caret != NO_CARET && caretOn) drawCaret(canvas, current)
    }

    private fun drawText(
        canvas: Canvas,
        edited: EditedBlock,
    ) {
        val scale = viewport.scale
        val pieces = edited.pieces
        for (i in pieces.indices) {
            val piece = pieces[i]
            val index = indexOf(piece.pageId)
            if (index < 0) continue
            canvas.save()
            canvas.translate(viewport.pageOriginViewX(index).toFloat(), viewport.pageOriginViewY(index).toFloat())
            canvas.scale(scale, scale)
            single.clear()
            single += piece.run
            painter.draw(canvas, single, piece.run.bounds, scale, textArgb)
            canvas.restore()
        }
        single.clear()
    }

    private fun drawCaret(
        canvas: Canvas,
        edited: EditedBlock,
    ) {
        val spot = edited.caretSpot(caret) ?: return
        val index = indexOf(spot.pageId)
        if (index < 0) return
        val scale = viewport.scale
        val x = viewport.pageOriginViewX(index).toFloat() + spot.xPt * scale
        val y = viewport.pageOriginViewY(index).toFloat()
        canvas.drawRect(x - caretWidthPx / 2, y + spot.topPt * scale, x + caretWidthPx / 2, y + spot.bottomPt * scale, caretPaint)
    }

    companion object {
        /** No caret: nothing edited or a range selected. */
        const val NO_CARET = -1

        // Half a blink period, the platform's text cursor rhythm.
        private const val BLINK_MS = 500L
        private const val CARET_DP = 2f

        // The caret in the accent, like the selection handles.
        private const val CARET_ARGB = 0xFF4B85E0.toInt()
    }
}
