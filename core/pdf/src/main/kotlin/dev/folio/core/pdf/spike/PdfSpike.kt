package dev.folio.core.pdf.spike

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.WorkerThread
import java.io.File
import kotlin.math.abs

/**
 * Spike P01-S5 fixtures and probes: a generated vector source PDF, annotation overlays drawn with
 * PdfDocument, PdfRenderer timings and a pixel check of merged output. Deleted or promoted by P01-T08.
 */
object PdfSpike {
    const val PAGE_WIDTH_PT = 595
    const val PAGE_HEIGHT_PT = 842

    /** A filled overlay mark at this page rect (pt) lets the pixel check find annotations. */
    const val MARK_LEFT_PT = 420f
    const val MARK_TOP_PT = 90f
    const val MARK_SIZE_PT = 40f

    private const val LINES_PER_PAGE = 34
    private const val LINE_START_PT = 140f
    private const val LINE_PITCH_PT = 20f
    private const val MARGIN_PT = 60f
    private const val TEXT_SIZE_PT = 11f
    private const val TITLE_SIZE_PT = 20f
    private const val IMAGE_PX = 64
    private const val IMAGE_TOP_PT = 40f
    private const val INK_WIDTH_PT = 1.6f
    private const val RULE_BELOW_PT = 4f
    private const val CURVE_STEP_PT = 120f
    private const val CURVE_AMP_PT = 18f
    private const val CURVE_LENGTH_PT = 360f
    private const val STROKES_PER_OVERLAY = 12
    private const val TILE_PX = 512
    private const val NS_PER_MS = 1_000_000.0
    private const val RED_MIN = 180
    private const val OTHER_MAX = 90
    private const val BYTE = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private val MARK_ARGB = Color.rgb(220, 30, 30)

    /** Writes a [pages]-page A4 PDF with text, rules and a small image on every page (all vector except the image). */
    @WorkerThread
    fun writeSourcePdf(
        file: File,
        pages: Int,
    ) {
        val document = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TEXT_SIZE_PT }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TITLE_SIZE_PT }
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 170, 200) }
        val image = gradientBitmap()
        for (index in 0 until pages) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, index + 1).create())
            val canvas = page.canvas
            canvas.drawText("Spike source page ${index + 1}", MARGIN_PT, MARGIN_PT + TITLE_SIZE_PT, title)
            canvas.drawBitmap(image, PAGE_WIDTH_PT - MARGIN_PT - IMAGE_PX, IMAGE_TOP_PT, null)
            for (line in 0 until LINES_PER_PAGE) {
                val y = LINE_START_PT + line * LINE_PITCH_PT
                canvas.drawLine(MARGIN_PT, y + RULE_BELOW_PT, PAGE_WIDTH_PT - MARGIN_PT, y + RULE_BELOW_PT, rule)
                canvas.drawText("Line $line of page ${index + 1}: the quick brown fox jumps over the lazy dog.", MARGIN_PT, y, text)
            }
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
        document.close()
        image.recycle()
    }

    /** Writes [count] transparent A4 overlay pages: ink-like curves, a label and the red mark. */
    @WorkerThread
    fun writeOverlayPdf(
        file: File,
        count: Int,
    ) {
        val document = PdfDocument()
        val ink =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 60, 180)
                style = Paint.Style.STROKE
                strokeWidth = INK_WIDTH_PT
                strokeCap = Paint.Cap.ROUND
            }
        val mark = Paint().apply { color = MARK_ARGB }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TEXT_SIZE_PT }
        val path = Path()
        for (index in 0 until count) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, index + 1).create())
            val canvas = page.canvas
            for (s in 0 until STROKES_PER_OVERLAY) {
                val y = LINE_START_PT + s * 2 * LINE_PITCH_PT
                path.reset()
                path.moveTo(MARGIN_PT, y)
                path.cubicTo(
                    MARGIN_PT + CURVE_STEP_PT,
                    y - CURVE_AMP_PT,
                    MARGIN_PT + 2 * CURVE_STEP_PT,
                    y + CURVE_AMP_PT,
                    MARGIN_PT + CURVE_LENGTH_PT,
                    y,
                )
                canvas.drawPath(path, ink)
            }
            canvas.drawRect(MARK_LEFT_PT, MARK_TOP_PT, MARK_LEFT_PT + MARK_SIZE_PT, MARK_TOP_PT + MARK_SIZE_PT, mark)
            canvas.drawText("Folio annotation ${index + 1}", MARGIN_PT, PAGE_HEIGHT_PT - MARGIN_PT, label)
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
        document.close()
    }

    /** Opens [file], renders a 0.5x preview of page 0 at [fitScale] px per pt; returns elapsed ms. */
    @WorkerThread
    fun firstPageMs(
        file: File,
        fitScale: Float,
    ): Double {
        val start = System.nanoTime()
        open(file) { renderer ->
            renderer.openPage(0).use { page ->
                val scale = fitScale / 2
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.recycle()
            }
        }
        return (System.nanoTime() - start) / NS_PER_MS
    }

    /** Renders the 512 px tile at the center of every page at [scale] px per pt; returns ms per page. */
    @WorkerThread
    fun centerTileMs(
        file: File,
        scale: Float,
    ): List<Double> {
        val times = ArrayList<Double>()
        val tile = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        open(file) { renderer ->
            for (index in 0 until renderer.pageCount) {
                val start = System.nanoTime()
                renderer.openPage(index).use { page ->
                    tile.eraseColor(Color.WHITE)
                    matrix.setScale(scale, scale)
                    matrix.postTranslate(-(page.width * scale - TILE_PX) / 2, -(page.height * scale - TILE_PX) / 2)
                    page.render(tile, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                times += (System.nanoTime() - start) / NS_PER_MS
            }
        }
        tile.recycle()
        return times
    }

    /** Page count and whether every page is A4 in pt. */
    @WorkerThread
    fun pageFacts(file: File): Pair<Int, Boolean> =
        open(file) { renderer ->
            val allA4 =
                (0 until renderer.pageCount).all { i ->
                    renderer.openPage(i).use { abs(it.width - PAGE_WIDTH_PT) <= 1 && abs(it.height - PAGE_HEIGHT_PT) <= 1 }
                }
            renderer.pageCount to allA4
        }

    /** True when the center of the overlay mark on page [index] renders red. */
    @WorkerThread
    fun markIsRed(
        file: File,
        index: Int,
    ): Boolean =
        open(file) { renderer ->
            renderer.openPage(index).use { page ->
                val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                pixel.eraseColor(Color.WHITE)
                val center = MARK_SIZE_PT / 2
                page.render(
                    pixel,
                    null,
                    Matrix().apply {
                        setTranslate(-(MARK_LEFT_PT + center), -(MARK_TOP_PT + center))
                    },
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                val argb = pixel.getPixel(0, 0)
                pixel.recycle()
                val red = argb shr RED_SHIFT and BYTE
                val green = argb shr GREEN_SHIFT and BYTE
                val blue = argb and BYTE
                red >= RED_MIN && green <= OTHER_MAX && blue <= OTHER_MAX
            }
        }

    private fun <T> open(
        file: File,
        block: (PdfRenderer) -> T,
    ): T = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(block) }

    private fun gradientBitmap(): Bitmap {
        val pixels = IntArray(IMAGE_PX * IMAGE_PX) { i -> Color.rgb(i % IMAGE_PX * 4, i / IMAGE_PX * 4, 128) }
        return Bitmap.createBitmap(pixels, IMAGE_PX, IMAGE_PX, Bitmap.Config.ARGB_8888)
    }
}
