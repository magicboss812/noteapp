package dev.folio.core.text.spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import dev.folio.core.common.Outcome
import dev.folio.core.text.math.MathLayout
import dev.folio.core.text.math.MathRenderer
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

/** Spike P01-S4 numbers for one [MathRenderer] over the corpus. */
class MathProbeResult(
    val renderer: String,
    val total: Int,
    /** Formulas that failed to lay out, with the error message. */
    val failures: List<String>,
    val layoutDrawP50Ms: Double,
    val layoutDrawP95Ms: Double,
    /** Largest distance drawn ink reaches outside the reported box, in px. */
    val maxInkOverflowPx: Float,
    /** Formulas whose ink leaves the box by more than [MathProbe.MAX_OVERFLOW_PX]. */
    val overflowing: List<String>,
) {
    val successPct: Double get() = (total - failures.size) * PERCENT / total

    private companion object {
        const val PERCENT = 100.0
    }
}

/**
 * Spike P01-S4: lays out and draws every corpus formula (inline style, about body size at the
 * text reference scale), timing layout + draw and comparing drawn ink with the reported box.
 * Deleted or promoted by P01-T08.
 */
object MathProbe {
    /** Body M at the text reference scale: about 12.5 pt x 4 px/pt. */
    const val FONT_SIZE_PX = 50f

    /** Metric accuracy budget (P01-S4). */
    const val MAX_OVERFLOW_PX = 1f

    private const val MARGIN_PX = 16
    private const val INK_ALPHA = 0x40
    private const val NS_PER_MS = 1_000_000.0
    private const val P95 = 0.95
    private const val ALPHA_SHIFT = 24
    private const val BYTE = 0xFF
    private const val INK_ARGB = 0xFF000000.toInt()

    /** Formulas of `testdata/math/corpus.txt`: one per line, `#` comments skipped. */
    fun parseCorpus(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Runs the corpus [repeats] times through [renderer]; timings cover every repeat, ink checks the first. */
    fun run(
        renderer: MathRenderer,
        corpus: List<String>,
        repeats: Int,
    ): MathProbeResult {
        val failures = mutableListOf<String>()
        val overflowing = mutableListOf<String>()
        val timesMs = ArrayList<Double>(corpus.size * repeats)
        val scratch = Bitmap.createBitmap(SCRATCH_PX, SCRATCH_PX, Bitmap.Config.ARGB_8888)
        val scratchCanvas = Canvas(scratch)
        var maxOverflow = 0f
        for (latex in corpus) {
            var layout: MathLayout? = null
            for (i in 0 until repeats) {
                val start = System.nanoTime()
                when (val result = renderer.layout(latex, FONT_SIZE_PX, INK_ARGB, display = false)) {
                    is Outcome.Success -> {
                        result.value.draw(scratchCanvas, 0f, FONT_SIZE_PX)
                        layout = result.value
                    }

                    is Outcome.Failure -> {
                        if (i == 0) failures += "$latex: ${result.cause?.message ?: result.message}"
                        break
                    }
                }
                timesMs += (System.nanoTime() - start) / NS_PER_MS
            }
            val overflow = layout?.let(::inkOverflowPx) ?: continue
            maxOverflow = max(maxOverflow, overflow)
            if (overflow > MAX_OVERFLOW_PX) overflowing += "$latex (%.1f px)".format(overflow)
        }
        scratch.recycle()
        timesMs.sort()
        return MathProbeResult(
            renderer = renderer.name,
            total = corpus.size,
            failures = failures,
            layoutDrawP50Ms = timesMs.getOrElse(timesMs.size / 2) { 0.0 },
            layoutDrawP95Ms = timesMs.getOrElse(((timesMs.size - 1) * P95).toInt()) { 0.0 },
            maxInkOverflowPx = maxOverflow,
            overflowing = overflowing,
        )
    }

    /** How far drawn ink (alpha >= 25%) leaves the formula's box, in px; 0 when inside. */
    fun inkOverflowPx(layout: MathLayout): Float {
        val box = layout.box
        val width = ceil(box.widthPx).toInt() + 2 * MARGIN_PX
        val height = ceil(box.ascentPx + box.depthPx).toInt() + 2 * MARGIN_PX
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap), MARGIN_PX.toFloat(), MARGIN_PX + box.ascentPx)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if ((row[x] ushr ALPHA_SHIFT and BYTE) < INK_ALPHA) continue
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        bitmap.recycle()
        if (right < 0) return 0f
        val boxLeft = MARGIN_PX.toFloat()
        val boxTop = MARGIN_PX.toFloat()
        return maxOf(
            0f,
            boxLeft - left,
            boxTop - top,
            right + 1 - (boxLeft + box.widthPx),
            bottom + 1 - (boxTop + box.ascentPx + box.depthPx),
        )
    }

    /**
     * Draws every formula that lays out onto PdfDocument pages and returns the PDF bytes. Vector
     * output means the file contains no image XObjects (`/Subtype /Image`). Device only.
     */
    fun renderPdf(
        renderer: MathRenderer,
        corpus: List<String>,
    ): ByteArray {
        val document = PdfDocument()
        corpus.forEachIndexed { index, latex ->
            val layout =
                (renderer.layout(latex, FONT_SIZE_PX, INK_ARGB, display = true) as? Outcome.Success)?.value ?: return@forEachIndexed
            val info = PdfDocument.PageInfo.Builder(PDF_PAGE_PX, PDF_PAGE_PX, index + 1).create()
            val page = document.startPage(info)
            layout.draw(page.canvas, MARGIN_PX.toFloat(), MARGIN_PX + layout.box.ascentPx)
            document.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    /** True when [pdf] holds no raster image objects. */
    fun isVectorOnly(pdf: ByteArray): Boolean = !String(pdf, Charsets.ISO_8859_1).contains("/Subtype /Image")

    /** A Markdown table of [results]. */
    fun table(results: List<MathProbeResult>): String =
        buildString {
            appendLine("| Renderer | Parsed | Layout+draw p50 / p95 ms | Max ink overflow px | Overflowing (> 1 px) |")
            appendLine("|---|---|---|---|---|")
            for (r in results) {
                appendLine(
                    "| ${r.renderer} | ${r.total - r.failures.size}/${r.total} (${"%.1f".format(r.successPct)}%) | " +
                        "${"%.2f".format(r.layoutDrawP50Ms)} / ${"%.2f".format(r.layoutDrawP95Ms)} | " +
                        "${"%.2f".format(r.maxInkOverflowPx)} | ${r.overflowing.size} |",
                )
            }
            for (r in results) {
                r.failures.forEach { appendLine("- ${r.renderer} failed: $it") }
                r.overflowing.forEach { appendLine("- ${r.renderer} overflow: $it") }
            }
        }

    private const val SCRATCH_PX = 2048
    private const val PDF_PAGE_PX = 1200
}
