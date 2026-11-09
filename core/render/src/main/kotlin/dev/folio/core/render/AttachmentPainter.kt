package dev.folio.core.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import dev.folio.core.model.Attachment
import dev.folio.core.model.attachment.AttachmentKind
import dev.folio.core.model.attachment.AttachmentLabels

/**
 * Paints attachment chips (A-070) in page space, so thumbnails and export show them too: a light gray rounded card with
 * a border, a colored tile with the file type ("PDF", "IMG", ...), the name cut to fit and the size below it. Not
 * thread-safe: one per [PageRenderer].
 */
internal class AttachmentPainter {
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_ARGB }
    private val border =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_ARGB
            style = Paint.Style.STROKE
            strokeWidth = BORDER_PT
        }
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tag =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = TAG_ARGB
            textSize = TAG_TEXT_PT
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    private val name =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = NAME_ARGB
            textSize = NAME_TEXT_PT
            typeface = Typeface.create(Typeface.DEFAULT, MEDIUM_WEIGHT, false)
        }
    private val size =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = SIZE_ARGB
            textSize = SIZE_TEXT_PT
        }
    private val rect = RectF()

    // Fitted names and size labels by their source, so a redraw allocates nothing (cleared when it grows large).
    private val fitted = HashMap<String, String>()
    private val sizes = HashMap<Long, String>()

    fun draw(
        canvas: Canvas,
        o: Attachment,
    ) {
        // HOT PATH: per chip per tile; strings are cached, no allocation once a chip was drawn.
        val x = o.position.x
        val y = o.position.y
        rect.set(x, y, x + Attachment.CHIP_WIDTH_PT, y + Attachment.CHIP_HEIGHT_PT)
        canvas.drawRoundRect(rect, RADIUS_PT, RADIUS_PT, card)
        canvas.drawRoundRect(rect, RADIUS_PT, RADIUS_PT, border)
        val kind = AttachmentLabels.kindOf(o.mime, o.displayName)
        val tileTop = y + (Attachment.CHIP_HEIGHT_PT - TILE_PT) / 2
        rect.set(x + PAD_PT, tileTop, x + PAD_PT + TILE_PT, tileTop + TILE_PT)
        tile.color = TILE_ARGB[kind.ordinal]
        canvas.drawRoundRect(rect, TILE_RADIUS_PT, TILE_RADIUS_PT, tile)
        canvas.drawText(TAGS[kind.ordinal], rect.centerX(), rect.centerY() + TAG_TEXT_PT * TAG_BASELINE, tag)
        val textX = rect.right + GAP_PT
        val textWidth = x + Attachment.CHIP_WIDTH_PT - PAD_PT - textX
        val label = sizeLabel(o.sizeBytes)
        val center = y + Attachment.CHIP_HEIGHT_PT / 2
        if (label.isEmpty()) {
            canvas.drawText(fit(o.displayName, textWidth), textX, center + NAME_TEXT_PT * NAME_ALONE_BASELINE, name)
        } else {
            canvas.drawText(fit(o.displayName, textWidth), textX, center - NAME_ABOVE_PT, name)
            canvas.drawText(label, textX, center + SIZE_BELOW_PT, size)
        }
    }

    private fun fit(
        text: String,
        widthPt: Float,
    ): String {
        fitted[text]?.let { return it }
        if (fitted.size > CACHE_LIMIT) fitted.clear()
        val shown = if (text.isBlank()) UNNAMED else TextUtils.ellipsize(text, name, widthPt, TextUtils.TruncateAt.MIDDLE).toString()
        fitted[text] = shown
        return shown
    }

    private fun sizeLabel(bytes: Long): String {
        sizes[bytes]?.let { return it }
        if (sizes.size > CACHE_LIMIT) sizes.clear()
        return AttachmentLabels.sizeLabel(bytes).also { sizes[bytes] = it }
    }

    private companion object {
        const val RADIUS_PT = 8f
        const val BORDER_PT = 1f
        const val PAD_PT = 6f
        const val TILE_PT = 30f
        const val TILE_RADIUS_PT = 6f
        const val GAP_PT = 8f
        const val TAG_TEXT_PT = 8.5f
        const val TAG_BASELINE = 0.36f
        const val NAME_TEXT_PT = 11f
        const val SIZE_TEXT_PT = 9f
        const val NAME_ABOVE_PT = 2.5f
        const val SIZE_BELOW_PT = 11f
        const val NAME_ALONE_BASELINE = 0.36f
        const val MEDIUM_WEIGHT = 500
        const val CACHE_LIMIT = 256
        const val UNNAMED = "Attachment"

        // Tinted and outlined so the card stands out on white paper too (device check P07-T07).
        const val CARD_ARGB = 0xFFF3F4F7.toInt()
        const val BORDER_ARGB = 0xFFB8BDC7.toInt()
        const val TAG_ARGB = 0xFFFFFFFF.toInt()
        const val NAME_ARGB = 0xFF1F2937.toInt()
        const val SIZE_ARGB = 0xFF6B7280.toInt()

        // By AttachmentKind ordinal: PDF, IMAGE, AUDIO, VIDEO, TEXT, SHEET, ARCHIVE, OTHER.
        val TAGS = arrayOf("PDF", "IMG", "AUD", "VID", "DOC", "XLS", "ZIP", "FILE")
        val TILE_ARGB =
            intArrayOf(
                0xFFDC2626.toInt(),
                0xFF16A34A.toInt(),
                0xFF9333EA.toInt(),
                0xFFDB2777.toInt(),
                0xFF2563EB.toInt(),
                0xFF15803D.toInt(),
                0xFFD97706.toInt(),
                0xFF6B7280.toInt(),
            )

        init {
            check(TAGS.size == AttachmentKind.entries.size && TILE_ARGB.size == AttachmentKind.entries.size)
        }
    }
}
