package dev.folio.core.pdf.export

import android.content.Context
import androidx.annotation.WorkerThread
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import dev.folio.core.common.Outcome
import dev.folio.core.common.outcomeOf
import java.io.File

/**
 * ADR-006 option A: draws one-page overlays (annotation layers rendered with PdfDocument) on top
 * of pages of an existing PDF as form XObjects, keeping the original page content untouched
 * (08-pdf.md#export). Pages without an overlay are copied as they are.
 */
class PdfBoxOverlayMerger(
    context: Context,
) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /**
     * Writes [output]: [original] with overlay page i of [overlay] appended on top of original
     * page [targetPages][i] (0-based). Overlay and target pages must have the same size.
     */
    @WorkerThread
    fun merge(
        original: File,
        overlay: File,
        targetPages: IntArray,
        output: File,
    ): Outcome<Unit> =
        outcomeOf("PDF overlay merge failed") {
            PDDocument.load(original, MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES)).use { target ->
                PDDocument.load(overlay, MemoryUsageSetting.setupMainMemoryOnly()).use { layers ->
                    require(
                        layers.numberOfPages == targetPages.size,
                    ) { "overlay has ${layers.numberOfPages} pages for ${targetPages.size} targets" }
                    val utility = LayerUtility(target)
                    targetPages.forEachIndexed { overlayIndex, pageIndex ->
                        val form = utility.importPageAsForm(layers, overlayIndex)
                        val page = target.getPage(pageIndex)
                        // Append mode with a reset context: the original content is wrapped in q/Q first.
                        PDPageContentStream(target, page, PDPageContentStream.AppendMode.APPEND, true, true).use { it.drawForm(form) }
                    }
                    target.save(output)
                }
            }
        }

    private companion object {
        const val MAX_MAIN_MEMORY_BYTES = 64L * 1024 * 1024
    }
}
