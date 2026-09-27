package dev.folio.core.pdf.spike

import android.content.Context
import androidx.annotation.WorkerThread
import dev.folio.core.common.Outcome
import dev.folio.core.pdf.export.PdfBoxOverlayMerger
import java.io.File
import java.util.Locale

/** One P01-S5 run: raster and export timings plus checks of the merged output. */
data class PdfProbeRun(
    val run: Int,
    val firstPageMs: Double,
    val tileZoom1P50Ms: Double,
    val tileZoom1P95Ms: Double,
    val tileZoom2P50Ms: Double,
    val tileZoom2P95Ms: Double,
    val overlayMs: Double,
    val exportMs: Double,
    val inBytes: Long,
    val outBytes: Long,
    val merged: Boolean,
    val pages: Int,
    val allA4: Boolean,
    val markOnAnnotated: Boolean,
    val markOnPlain: Boolean,
) {
    /** One log line with every number (Locale.ROOT). */
    fun line(): String =
        "run=$run firstPageMs=%.1f tileZ1 p50/p95=%.1f/%.1f tileZ2 p50/p95=%.1f/%.1f overlayMs=%.0f exportMs=%.0f "
            .format(Locale.ROOT, firstPageMs, tileZoom1P50Ms, tileZoom1P95Ms, tileZoom2P50Ms, tileZoom2P95Ms, overlayMs, exportMs) +
            "inBytes=$inBytes outBytes=$outBytes merged=$merged pages=$pages allA4=$allA4 " +
            "markAnnotated=$markOnAnnotated markPlain=$markOnPlain"
}

/**
 * Spike P01-S5 protocol shared by the instrumented test and the `spike-pdf` debug route: a
 * generated 100-page vector PDF, center-tile raster at zoom 1 and 2, export of 10 overlays
 * merged with PdfBox, and PdfRenderer checks of the output. Deleted or promoted by P01-T08.
 */
object PdfSpikeProbe {
    const val PAGES = 100
    const val PLAIN_PAGE = 1

    /** Pad 7 landscape fit-width px per pt for A4. */
    const val FIT_SCALE = 5.154f
    val ANNOTATED = intArrayOf(0, 9, 19, 29, 39, 49, 59, 69, 79, 99)
    private const val NS_PER_MS = 1_000_000.0
    private const val P50 = 0.5
    private const val P95 = 0.95

    /** Writes the source PDF into [dir] once and measures [runs] runs. */
    @WorkerThread
    fun run(
        context: Context,
        dir: File,
        runs: Int,
    ): List<PdfProbeRun> {
        dir.mkdirs()
        val source = File(dir, "source.pdf")
        PdfSpike.writeSourcePdf(source, PAGES)
        val merger = PdfBoxOverlayMerger(context)
        return (1..runs).map { run -> runOnce(run, source, dir, merger) }
    }

    private fun runOnce(
        run: Int,
        source: File,
        dir: File,
        merger: PdfBoxOverlayMerger,
    ): PdfProbeRun {
        val firstMs = PdfSpike.firstPageMs(source, FIT_SCALE)
        val zoom1 = PdfSpike.centerTileMs(source, FIT_SCALE).sorted()
        val zoom2 = PdfSpike.centerTileMs(source, 2 * FIT_SCALE).sorted()
        val overlay = File(dir, "overlay.pdf")
        val output = File(dir, "output.pdf")
        output.delete()
        val exportStart = System.nanoTime()
        PdfSpike.writeOverlayPdf(overlay, ANNOTATED.size)
        val overlayMs = (System.nanoTime() - exportStart) / NS_PER_MS
        val merged = merger.merge(source, overlay, ANNOTATED, output) is Outcome.Success
        val exportMs = (System.nanoTime() - exportStart) / NS_PER_MS
        val (pages, allA4) = if (merged) PdfSpike.pageFacts(output) else 0 to false
        return PdfProbeRun(
            run = run,
            firstPageMs = firstMs,
            tileZoom1P50Ms = zoom1.quantile(P50),
            tileZoom1P95Ms = zoom1.quantile(P95),
            tileZoom2P50Ms = zoom2.quantile(P50),
            tileZoom2P95Ms = zoom2.quantile(P95),
            overlayMs = overlayMs,
            exportMs = exportMs,
            inBytes = source.length(),
            outBytes = output.length(),
            merged = merged,
            pages = pages,
            allA4 = allA4,
            markOnAnnotated = merged && PdfSpike.markIsRed(output, ANNOTATED.first()),
            markOnPlain = merged && PdfSpike.markIsRed(output, PLAIN_PAGE),
        )
    }

    private fun List<Double>.quantile(q: Double): Double = this[((size - 1) * q).toInt()]
}
