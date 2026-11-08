package dev.folio.feature.editor.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.model.TextAlign
import dev.folio.core.text.fonts.BodySize
import dev.folio.core.text.fonts.TextSizing
import dev.folio.feature.editor.text.BoxLooks
import kotlin.math.abs
import kotlin.math.roundToInt

/** The buttons of the box toolbar, left to right (10-editor-ui.md#text-boxes, Box toolbar). */
internal enum class BoxButton(
    /** False while the task that makes it work is still to come (laid out, not shown). */
    val shown: Boolean,
) {
    EDIT(true),
    COLOR(true),
    SIZE(true),
    ALIGN(true),
    FILL(true),
    DELETE(true),
}

/** The popups of the box toolbar: the button that opens each and its number of items, left to right. */
internal enum class BoxPopup(
    val button: BoxButton,
    val items: Int,
) {
    /** Auto, then the pen palette (P07-T17). */
    COLOR(BoxButton.COLOR, 1 + BrushPresets.PEN_PALETTE.size),

    /** Smaller, S, M, L, larger (P07-T17). */
    SIZE(BoxButton.SIZE, 5),

    /** Left, Center, Right, Justify (P07-T16). */
    ALIGN(BoxButton.ALIGN, 4),
}

/**
 * Where the box toolbar goes and what it shows (10-editor-ui.md#text-boxes): a dark pill of constant screen size,
 * centered above the box's outline, below it when there is no room above, kept inside the view. Pure geometry in view
 * px so tests place it without a canvas; [draw] paints it.
 */
@Suppress("TooManyFunctions") // placing, hit testing and painting of the pill and its three popups
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

    /** The open popup in view px (one row of items); empty until [placePopup]. */
    val popup = RectF()

    /** The popup [placePopup] placed last, null before. */
    var popupKind: BoxPopup? = null
        private set

    /**
     * Places popup [kind] after [place]: centered on its button, on the side of the pill away from the box (above it,
     * below it when the pill sits below the box), kept inside a view [viewWidthPx] x [viewHeightPx].
     */
    fun placePopup(
        kind: BoxPopup,
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

    /** The item at ([xPx], [yPx]) in the popup placed last, -1 outside it. */
    fun itemAt(
        xPx: Float,
        yPx: Float,
    ): Int {
        val kind = popupKind ?: return -1
        if (popup.isEmpty || !popup.contains(xPx, yPx)) return -1
        return ((xPx - popup.left - padPx) / buttonPx).toInt().coerceIn(0, kind.items - 1)
    }

    /** The alignment at ([xPx], [yPx]) in the Alignment popup placed last, null outside it or for another popup. */
    fun alignAt(
        xPx: Float,
        yPx: Float,
    ): TextAlign? = if (popupKind == BoxPopup.ALIGN) alignOf(itemAt(xPx, yPx)) else null

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
    private val fillWidth: Path = PathParser.createPathFromPathData(MOVE_HORIZONTAL)
    private val alignIcons: Array<Path> = Array(ALIGNS.size) { PathParser.createPathFromPathData(ALIGN_PATHS[it]) }
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ICON_ARGB
            style = Paint.Style.STROKE
            strokeWidth = RING_DP * density
        }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ICON_ARGB
            textAlign = Paint.Align.CENTER
            textSize = LABEL_DP * density
            typeface = Typeface.DEFAULT_BOLD
        }

    // The Font size button's number, made again only when the rounded size changes (drawing allocates nothing).
    private var sizeShown = -1
    private var sizeLabel = ""

    /**
     * Paints the pill at [pill]; the Edit button shows [editing] as active, the Fill width button [fill], the Alignment
     * button the icon of [align], the Color button a circle of the box's color ([looks], [autoArgb] for Auto) and the
     * Font size button its size in pt.
     */
    fun draw(
        canvas: Canvas,
        editing: Boolean,
        fill: Boolean = false,
        align: TextAlign = TextAlign.START,
        looks: BoxLooks = BoxLooks(),
        autoArgb: Int = AUTO_FALLBACK_ARGB,
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

                BoxButton.FILL -> {
                    if (fill) canvas.drawCircle(cx, cy, buttonPx / 2 - padPx / 2, activePaint)
                    icon(canvas, fillWidth, cx, cy, ICON_ARGB)
                }

                BoxButton.ALIGN -> {
                    icon(canvas, alignIcons[ALIGNS.indexOf(align)], cx, cy, ICON_ARGB)
                }

                BoxButton.COLOR -> {
                    swatch(canvas, cx, cy, looks.textArgb ?: autoArgb)
                }

                BoxButton.SIZE -> {
                    val pt = looks.sizePt.roundToInt()
                    if (pt != sizeShown) {
                        sizeShown = pt
                        sizeLabel = pt.toString()
                    }
                    label(canvas, sizeLabel, cx, cy)
                }
            }
        }
    }

    /**
     * Paints the open popup at [popup] with the box's current choice highlighted: the Alignment icons ([align]), the
     * sizes (a preset equal to [looks]' size), or Auto ([autoArgb], marked "A") and the pen palette.
     */
    fun drawPopup(
        canvas: Canvas,
        align: TextAlign,
        looks: BoxLooks,
        autoArgb: Int,
    ) {
        // HOT PATH: per viewport change while the popup is open; no allocation.
        val kind = popupKind ?: return
        if (popup.isEmpty) return
        val radius = heightPx / 2
        canvas.drawRoundRect(popup, radius, radius, pillPaint)
        canvas.drawRoundRect(popup, radius, radius, borderPaint)
        val cy = popup.centerY()
        for (i in 0 until kind.items) {
            val cx = popup.left + padPx + i * buttonPx + buttonPx / 2
            if (isCurrent(kind, i, align, looks)) canvas.drawCircle(cx, cy, buttonPx / 2 - padPx / 2, activePaint)
            when (kind) {
                BoxPopup.ALIGN -> {
                    icon(canvas, alignIcons[i], cx, cy, ICON_ARGB)
                }

                BoxPopup.SIZE -> {
                    label(canvas, SIZE_LABELS[i], cx, cy)
                }

                BoxPopup.COLOR -> {
                    swatch(canvas, cx, cy, colorOf(i) ?: autoArgb)
                    if (i == 0) label(canvas, AUTO_LABEL, cx, cy, if (luma(autoArgb) < DARK_LUMA) ICON_ARGB else PILL_ARGB)
                }
            }
        }
    }

    // True when item [i] of popup [kind] is the box's current choice.
    private fun isCurrent(
        kind: BoxPopup,
        i: Int,
        align: TextAlign,
        looks: BoxLooks,
    ): Boolean =
        when (kind) {
            BoxPopup.ALIGN -> ALIGNS[i] == align
            BoxPopup.SIZE -> i in 1..BodySize.entries.size && abs(BodySize.entries[i - 1].capFraction - looks.sizeRatio) < SAME_SIZE
            BoxPopup.COLOR -> colorOf(i) == looks.textArgb
        }

    // A filled circle of [argb] with a light ring, so dark colors show on the dark pill.
    private fun swatch(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        argb: Int,
    ) {
        val r = SWATCH_DP * density
        swatchPaint.color = argb
        canvas.drawCircle(cx, cy, r, swatchPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
    }

    // [text] centered on ([cx], [cy]).
    private fun label(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        argb: Int = ICON_ARGB,
    ) {
        labelPaint.color = argb
        canvas.drawText(text, cx, cy - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
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
        /** Alignment of Alignment popup item [item], null for none. */
        fun alignOf(item: Int): TextAlign? = ALIGNS.getOrNull(item)

        /** Color of Color popup item [item]: null for Auto (item 0), then the pen palette. */
        fun colorOf(item: Int): Int? = if (item <= 0) null else BrushPresets.PEN_PALETTE.getOrNull(item - 1)

        /** Body size (cap fraction) of Font size popup item [item] for a box at [current]: smaller, S, M, L, larger. */
        fun sizeOf(
            item: Int,
            current: Float,
        ): Float =
            when (item) {
                0 -> TextSizing.steppedBodyCap(current, up = false)
                in 1..BodySize.entries.size -> BodySize.entries[item - 1].capFraction
                else -> TextSizing.steppedBodyCap(current, up = true)
            }

        private fun luma(argb: Int): Float = LUMA_R * Color.red(argb) + LUMA_G * Color.green(argb) + LUMA_B * Color.blue(argb)

        private const val LUMA_R = 0.299f
        private const val LUMA_G = 0.587f
        private const val LUMA_B = 0.114f
        private const val DARK_LUMA = 110f

        // Font size popup labels: smaller and larger are a minus and a plus sign.
        private val SIZE_LABELS = arrayOf("−", "S", "M", "L", "+")
        private const val AUTO_LABEL = "A"

        /** Two body sizes closer than this are the same preset. */
        private const val SAME_SIZE = 0.001f

        /** Auto's color when the host gives none (near-black text on light paper). */
        private const val AUTO_FALLBACK_ARGB = 0xFF1B1D22.toInt()
        private const val SWATCH_DP = 9f
        private const val RING_DP = 1.5f
        private const val LABEL_DP = 15f

        private const val BUTTON_DP = 44f
        private const val HEIGHT_DP = 48f
        private const val PAD_DP = 4f
        private const val DIVIDER_GAP_DP = 9f
        private const val ICON_DP = 22f

        /** Between the pill and the box outline (clears the grip). */
        private const val GAP_DP = 14f

        /** The pill keeps this far from the view edges. */
        private const val MARGIN_DP = 8f

        /** Between the toolbar and its popups. */
        private const val POPUP_GAP_DP = 6f

        /** The popup's alignments, left to right. */
        private val ALIGNS = arrayOf(TextAlign.START, TextAlign.CENTER, TextAlign.END, TextAlign.JUSTIFIED)

        // Lucide `text-align-start`, `-center`, `-end` and `-justify` (core:designsystem FolioIcons), one path each.
        private val ALIGN_PATHS =
            arrayOf(
                "M21 5H3M15 12H3M17 19H3",
                "M21 5H3M17 12H7M19 19H5",
                "M21 5H3M21 12H9M21 19H7",
                "M3 5h18M3 12h18M3 19h18",
            )
        private const val ICON_VIEWPORT = 24f
        private const val ICON_STROKE = 2f

        // The Notewise toolbar look (docs/design/reference/text-box-toolbar-reference.png); named, no theme in the canvas.
        private const val PILL_ARGB = 0xF0202023.toInt()
        private const val BORDER_ARGB = 0xFF46464C.toInt()
        private const val ICON_ARGB = 0xFFE8E8EC.toInt()
        private const val ACTIVE_ARGB = 0xFF4B85E0.toInt()
        private const val DELETE_ARGB = 0xFFF4A39E.toInt()

        // Lucide `move-horizontal` (fill width), with absolute moves so its three parts make one path.
        private const val MOVE_HORIZONTAL = "M18 8l4 4-4 4M2 12h20M6 8l-4 4 4 4"

        // Lucide `pencil` and `trash` (core:designsystem FolioIcons), as one path each.
        private const val PENCIL =
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622" +
                "l4.353-1.32a2 2 0 0 0 .83-.497zM15 5l4 4"
        private const val TRASH =
            "M10 11v6M14 11v6M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
    }
}
