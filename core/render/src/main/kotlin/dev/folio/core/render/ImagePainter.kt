package dev.folio.core.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import dev.folio.core.model.ImageObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Paints [ImageObject]s: the cropped part of the decoded asset into the rect, turned around its center. */
internal class ImagePainter(
    private val images: ImageSource,
) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint().apply { color = PLACEHOLDER_ARGB }
    private val src = Rect()
    private val dst = RectF()

    fun draw(
        canvas: Canvas,
        o: ImageObject,
        scale: Float,
    ) {
        // HOT PATH: per image per tile; no allocation (the crop math of ImageCrops.sourcePx inlined).
        val r = o.rect
        val c = o.crop
        dst.set(r.left, r.top, r.right, r.bottom)
        // Pixels the whole (uncropped) source needs for the shown part to be sharp at [scale].
        val needW = ceil(r.widthPt * scale / (c.right - c.left)).toInt().coerceAtLeast(1)
        val needH = ceil(r.heightPt * scale / (c.bottom - c.top)).toInt().coerceAtLeast(1)
        val bitmap = images.bitmap(o.asset, needW, needH)
        canvas.save()
        if (o.rotationDeg != 0f) canvas.rotate(o.rotationDeg, dst.centerX(), dst.centerY())
        if (bitmap == null) {
            canvas.drawRect(dst, placeholder)
        } else {
            val w = bitmap.width
            val h = bitmap.height
            val left = (c.left * w).roundToInt().coerceIn(0, w - 1)
            val top = (c.top * h).roundToInt().coerceIn(0, h - 1)
            src.set(left, top, (c.right * w).roundToInt().coerceIn(left + 1, w), (c.bottom * h).roundToInt().coerceIn(top + 1, h))
            canvas.drawBitmap(bitmap, src, dst, paint)
        }
        canvas.restore()
    }

    private companion object {
        const val PLACEHOLDER_ARGB = 0xFFE3E5EA.toInt()
    }
}
