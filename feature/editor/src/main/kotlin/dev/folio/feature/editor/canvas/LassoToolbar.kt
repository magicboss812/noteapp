package dev.folio.feature.editor.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.model.edit.ReorderOp

/** The buttons of the lasso action bar (06-ink-input.md#lasso), left to right; [PASTE] alone in the paste pill. */
internal enum class LassoButton {
    CUT,
    COPY,
    DUPLICATE,
    COLOR,
    ARRANGE,
    DELETE,
    PASTE,
}

/** The popups of the lasso action bar: the button that opens each and its number of items. */
internal enum class LassoPopup(
    val button: LassoButton,
    val items: Int,
) {
    /** The pen palette. */
    COLOR(LassoButton.COLOR, BrushPresets.PEN_PALETTE.size),

    /** To front, forward, backward, to back. */
    ARRANGE(LassoButton.ARRANGE, 4),
}

/**
 * The lasso action bar (06-ink-input.md#lasso) in the box toolbar's look: a dark pill of constant screen size, centered
 * above the selection box, below it when there is no room above, kept inside the view; with nothing selected and a
 * filled clipboard, a pill with Paste alone at the pen tap. Pure geometry in view px; [draw] paints it.
 */
internal class LassoToolbar(
    private val density: Float,
) {
    private val selectionButtons = LassoButton.entries.filter { it != LassoButton.PASTE }
    private val pasteButtons = listOf(LassoButton.PASTE)
    private var buttons = selectionButtons
    private val buttonPx = BUTTON_DP * density
    private val heightPx = HEIGHT_DP * density
    private val padPx = PAD_DP * density
    private val dividerPx = DIVIDER_GAP_DP * density
    private val gapPx = GAP_DP * density
    private val marginPx = MARGIN_DP * density

    /** The pill in view px; empty until [place] (and after [clear]). */
    val pill = RectF()

    /** The open popup in view px; empty while none is open. */
    val popup = RectF()

    /** The open popup, null while none is. */
    var popupKind: LassoPopup? = null
        private set

    private var below = false

    private val widthPx: Float
        get() = 2 * padPx + buttons.size * buttonPx + if (LassoButton.DELETE in buttons) dividerPx else 0f

    /**
     * Places the pill for a selection box from [topPx] to [bottomPx] centered on [centerXPx] ([paste]: the Paste pill
     * above that point) in a view [viewWidthPx] x [viewHeightPx]; an open popup moves along.
     */
    fun place(
        centerXPx: Float,
        topPx: Float,
        bottomPx: Float,
        viewWidthPx: Float,
        viewHeightPx: Float,
        paste: Boolean = false,
    ) {
        // HOT PATH: per frame while a selection shows; no allocation.
        buttons = if (paste) pasteButtons else selectionButtons
        val width = widthPx
        val left = (centerXPx - width / 2).coerceIn(marginPx, maxOf(marginPx, viewWidthPx - marginPx - width))
        val above = topPx - gapPx - heightPx
        below = above < marginPx
        val top = (if (below) bottomPx + gapPx else above).coerceIn(marginPx, maxOf(marginPx, viewHeightPx - marginPx - heightPx))
        pill.set(left, top, left + width, top + heightPx)
        popupKind?.let { placePopup(it, viewWidthPx, viewHeightPx) }
    }

    /** Hides the pill and its popup. */
    fun clear() {
        pill.setEmpty()
        closePopup()
    }

    /** The button at ([xPx], [yPx]), null outside the pill. */
    fun buttonAt(
        xPx: Float,
        yPx: Float,
    ): LassoButton? {
        if (pill.isEmpty || !pill.contains(xPx, yPx)) return null
        for (i in buttons.indices) if (xPx < buttonLeft(i) + buttonPx) return buttons[i]
        return buttons.last()
    }

    /** True when ([xPx], [yPx]) is on the pill or the open popup. */
    fun covers(
        xPx: Float,
        yPx: Float,
    ): Boolean = (!pill.isEmpty && pill.contains(xPx, yPx)) || (!popup.isEmpty && popup.contains(xPx, yPx))

    // Left edge of button [i]: the divider sits before Delete.
    private fun buttonLeft(i: Int): Float {
        val divider = if (buttons[i] == LassoButton.DELETE && i > 0) dividerPx else 0f
        return pill.left + padPx + i * buttonPx + divider
    }

    /** Opens popup [kind] over its button, on the side of the pill away from the box, inside the view. */
    fun placePopup(
        kind: LassoPopup,
        viewWidthPx: Float,
        viewHeightPx: Float,
    ) {
        popupKind = kind
        val index = buttons.indexOf(kind.button)
        if (index < 0) return popup.setEmpty()
        val width = 2 * padPx + kind.items * buttonPx
        val cx = buttonLeft(index) + buttonPx / 2
        val left = (cx - width / 2).coerceIn(marginPx, maxOf(marginPx, viewWidthPx - marginPx - width))
        val wanted = if (below) pill.bottom + POPUP_GAP_DP * density else pill.top - POPUP_GAP_DP * density - heightPx
        val top = wanted.coerceIn(marginPx, maxOf(marginPx, viewHeightPx - marginPx - heightPx))
        popup.set(left, top, left + width, top + heightPx)
    }

    /** Closes the popup. */
    fun closePopup() {
        popupKind = null
        popup.setEmpty()
    }

    /** The item at ([xPx], [yPx]) in the open popup, -1 outside it. */
    fun itemAt(
        xPx: Float,
        yPx: Float,
    ): Int {
        val kind = popupKind ?: return -1
        if (popup.isEmpty || !popup.contains(xPx, yPx)) return -1
        return ((xPx - popup.left - padPx) / buttonPx).toInt().coerceIn(0, kind.items - 1)
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
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ICON_ARGB
            style = Paint.Style.STROKE
            strokeWidth = RING_DP * density
        }
    private val icons: Map<LassoButton, Path> = BUTTON_PATHS.mapValues { PathParser.createPathFromPathData(it.value) }
    private val arrangeIcons: Array<Path> = Array(ARRANGE_PATHS.size) { PathParser.createPathFromPathData(ARRANGE_PATHS[it]) }

    /** Paints the pill and the open popup; the button of the open popup shows as active. */
    fun draw(canvas: Canvas) {
        // HOT PATH: per frame while a selection shows; no allocation.
        if (pill.isEmpty) return
        val radius = heightPx / 2
        canvas.drawRoundRect(pill, radius, radius, pillPaint)
        canvas.drawRoundRect(pill, radius, radius, borderPaint)
        val cy = pill.centerY()
        for (i in buttons.indices) {
            val button = buttons[i]
            val cx = buttonLeft(i) + buttonPx / 2
            if (button == LassoButton.DELETE && i > 0) {
                val x = buttonLeft(i) - dividerPx / 2
                canvas.drawLine(x, pill.top + padPx * 2, x, pill.bottom - padPx * 2, dividerPaint)
            }
            if (popupKind?.button == button) canvas.drawCircle(cx, cy, buttonPx / 2 - padPx / 2, activePaint)
            icon(canvas, icons.getValue(button), cx, cy, if (button == LassoButton.DELETE) DELETE_ARGB else ICON_ARGB)
        }
        drawPopup(canvas)
    }

    private fun drawPopup(canvas: Canvas) {
        val kind = popupKind ?: return
        if (popup.isEmpty) return
        val radius = heightPx / 2
        canvas.drawRoundRect(popup, radius, radius, pillPaint)
        canvas.drawRoundRect(popup, radius, radius, borderPaint)
        val cy = popup.centerY()
        for (i in 0 until kind.items) {
            val cx = popup.left + padPx + i * buttonPx + buttonPx / 2
            when (kind) {
                LassoPopup.COLOR -> {
                    swatchPaint.color = BrushPresets.PEN_PALETTE[i]
                    canvas.drawCircle(cx, cy, SWATCH_DP * density, swatchPaint)
                    canvas.drawCircle(cx, cy, SWATCH_DP * density, ringPaint)
                }

                LassoPopup.ARRANGE -> {
                    icon(canvas, arrangeIcons[i], cx, cy, ICON_ARGB)
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
        /** Color of Color popup item [item], null outside the palette. */
        fun colorOf(item: Int): Int? = BrushPresets.PEN_PALETTE.getOrNull(item)

        /** Z-order change of Arrange popup item [item], null outside the popup. */
        fun reorderOf(item: Int): ReorderOp? = ARRANGE_OPS.getOrNull(item)

        private val ARRANGE_OPS = arrayOf(ReorderOp.BRING_TO_FRONT, ReorderOp.FORWARD, ReorderOp.BACKWARD, ReorderOp.SEND_TO_BACK)

        // The box toolbar's sizes and colors (BoxToolbar), so both bars look alike.
        private const val BUTTON_DP = 44f
        private const val HEIGHT_DP = 48f
        private const val PAD_DP = 4f
        private const val DIVIDER_GAP_DP = 9f
        private const val ICON_DP = 22f
        private const val GAP_DP = 14f
        private const val MARGIN_DP = 8f
        private const val POPUP_GAP_DP = 6f
        private const val SWATCH_DP = 9f
        private const val RING_DP = 1.5f
        private const val ICON_VIEWPORT = 24f
        private const val ICON_STROKE = 2f
        private const val PILL_ARGB = 0xF0202023.toInt()
        private const val BORDER_ARGB = 0xFF46464C.toInt()
        private const val ICON_ARGB = 0xFFE8E8EC.toInt()
        private const val ACTIVE_ARGB = 0xFF4B85E0.toInt()
        private const val DELETE_ARGB = 0xFFF4A39E.toInt()

        private const val COPY_PATH =
            "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z" +
                "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"

        // Lucide `scissors`, `copy`, `copy-plus`, `droplet`, `layers`, `trash` and `clipboard`, one path each (circles as arcs).
        private val BUTTON_PATHS =
            mapOf(
                LassoButton.CUT to
                    "M3 6a3 3 0 1 0 6 0a3 3 0 1 0-6 0M3 18a3 3 0 1 0 6 0a3 3 0 1 0-6 0M20 4L8.12 15.88M14.47 14.48L20 20M8.12 8.12L12 12",
                LassoButton.COPY to COPY_PATH,
                LassoButton.DUPLICATE to "M15 12v6M12 15h6$COPY_PATH",
                LassoButton.COLOR to
                    "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z",
                LassoButton.ARRANGE to
                    "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z" +
                    "M2 12l9.17 4.17a2 2 0 0 0 1.66 0L22 12M2 17l9.17 4.17a2 2 0 0 0 1.66 0L22 17",
                LassoButton.DELETE to
                    "M10 11v6M14 11v6M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
                LassoButton.PASTE to
                    "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z" +
                    "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",
            )

        // Lucide `arrow-up-to-line`, `arrow-up`, `arrow-down`, `arrow-down-to-line`.
        private val ARRANGE_PATHS =
            arrayOf(
                "M5 3h14M18 13l-6-6-6 6M12 7v14",
                "M5 12l7-7 7 7M12 19V5",
                "M12 5v14M19 12l-7 7-7-7",
                "M12 17V3M6 11l6 6 6-6M19 21H5",
            )
    }
}
