package dev.folio.feature.editor.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/** The buttons of the box toolbar, left to right (10-editor-ui.md#text-boxes, Box toolbar). */
internal enum class BoxButton(
    /** False while the task that makes it work is still to come (laid out, not shown). */
    val shown: Boolean,
) {
    EDIT(true),
    COLOR(false), // P07-T17
    SIZE(false), // P07-T17
    ALIGN(false), // P07-T16
    FILL(false), // P07-T15
    DELETE(true),
}

/**
 * Where the box toolbar goes and what it shows (10-editor-ui.md#text-boxes): a dark pill of constant screen size,
 * centered above the box's outline, below it when there is no room above, kept inside the view. Pure geometry in view
 * px so tests place it without a canvas; [draw] paints it.
 */
internal class BoxToolbar(
    private val density: Float,
) {
    private val buttons = BoxButton.entries.filter { it.shown }
    private val buttonPx = BUTTON_DP * density
    private val heightPx = HEIGHT_DP * density
    private val padPx = PAD_DP * density
    private val dividerPx = DIVIDER_GAP_DP * density
    private val gapPx = GAP_DP * density
    private val marginPx = MARGIN_DP * density

    /** The pill in view px; empty until [place]. */
    val pill = RectF()

    /** True when the last [place] put the pill below the box. */
    var below = false
        private set

    private val widthPx: Float
        get() = 2 * padPx + buttons.size * buttonPx + if (buttons.contains(BoxButton.DELETE) && buttons.size > 1) dividerPx else 0f

    /** Places the pill for a box outline from [topPx] to [bottomPx], centered on [centerXPx], in a view [viewWidthPx] x [viewHeightPx]. */
    fun place(
        centerXPx: Float,
        topPx: Float,
        bottomPx: Float,
        viewWidthPx: Float,
        viewHeightPx: Float,
    ) {
        val width = widthPx
        val left = (centerXPx - width / 2).coerceIn(marginPx, maxOf(marginPx, viewWidthPx - marginPx - width))
        val above = topPx - gapPx - heightPx
        below = above < marginPx
        val top = (if (below) bottomPx + gapPx else above).coerceIn(marginPx, maxOf(marginPx, viewHeightPx - marginPx - heightPx))
        pill.set(left, top, left + width, top + heightPx)
    }

    /** The button at ([xPx], [yPx]), null outside the pill. */
    fun buttonAt(
        xPx: Float,
        yPx: Float,
    ): BoxButton? {
        if (!pill.contains(xPx, yPx)) return null
        for (i in buttons.indices) if (xPx < buttonLeft(i) + buttonPx) return buttons[i]
        return buttons.last()
    }

    // Left edge of button [i]: the divider sits before Delete.
    private fun buttonLeft(i: Int): Float {
        val divider = if (buttons[i] == BoxButton.DELETE && i > 0) dividerPx else 0f
        return pill.left + padPx + i * buttonPx + divider
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PILL_ARGB }
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_ARGB
            style = Paint.Style.STROKE
            strokeWidth = density
        }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACTIVE_ARGB }
    private val dividerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_ARGB
            strokeWidth = density
        }
    private val iconPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ICON_STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val pencil: Path = PathParser.createPathFromPathData(PENCIL)
    private val trash: Path = PathParser.createPathFromPathData(TRASH)

    /** Paints the pill at [pill]; the Edit button shows [editing] as active. */
    fun draw(
        canvas: Canvas,
        editing: Boolean,
    ) {
        // HOT PATH: per viewport change while a box is selected; no allocation.
        val radius = heightPx / 2
        canvas.drawRoundRect(pill, radius, radius, pillPaint)
        canvas.drawRoundRect(pill, radius, radius, borderPaint)
        val cy = pill.centerY()
        for (i in buttons.indices) {
            val button = buttons[i]
            val cx = buttonLeft(i) + buttonPx / 2
            if (button == BoxButton.DELETE && i > 0) {
                val x = buttonLeft(i) - dividerPx / 2
                canvas.drawLine(x, pill.top + padPx * 2, x, pill.bottom - padPx * 2, dividerPaint)
            }
            when (button) {
                BoxButton.EDIT -> {
                    if (editing) canvas.drawCircle(cx, cy, buttonPx / 2 - padPx / 2, activePaint)
                    icon(canvas, pencil, cx, cy, ICON_ARGB)
                }

                BoxButton.DELETE -> {
                    icon(canvas, trash, cx, cy, DELETE_ARGB)
                }

                BoxButton.COLOR, BoxButton.SIZE, BoxButton.ALIGN, BoxButton.FILL -> {
                    Unit
                }
            }
        }
    }

    // A Lucide icon (24 x 24 viewport) centered on ([cx], [cy]) at the icon size.
    private fun icon(
        canvas: Canvas,
        path: Path,
        cx: Float,
        cy: Float,
        argb: Int,
    ) {
        val size = ICON_DP * density
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        canvas.scale(size / ICON_VIEWPORT, size / ICON_VIEWPORT)
        iconPaint.color = argb
        canvas.drawPath(path, iconPaint)
        canvas.restore()
    }

    companion object {
        private const val BUTTON_DP = 44f
        private const val HEIGHT_DP = 48f
        private const val PAD_DP = 4f
        private const val DIVIDER_GAP_DP = 9f
        private const val ICON_DP = 22f

        /** Between the pill and the box outline (clears the grip). */
        private const val GAP_DP = 14f

        /** The pill keeps this far from the view edges. */
        private const val MARGIN_DP = 8f
        private const val ICON_VIEWPORT = 24f
        private const val ICON_STROKE = 2f

        // The Notewise toolbar look (docs/design/reference/text-box-toolbar-reference.png); named, no theme in the canvas.
        private const val PILL_ARGB = 0xF0202023.toInt()
        private const val BORDER_ARGB = 0xFF46464C.toInt()
        private const val ICON_ARGB = 0xFFE8E8EC.toInt()
        private const val ACTIVE_ARGB = 0xFF4B85E0.toInt()
        private const val DELETE_ARGB = 0xFFF4A39E.toInt()

        // Lucide `pencil` and `trash` (core:designsystem FolioIcons), as one path each.
        private const val PENCIL =
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622" +
                "l4.353-1.32a2 2 0 0 0 .83-.497zM15 5l4 4"
        private const val TRASH =
            "M10 11v6M14 11v6M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
    }
}
