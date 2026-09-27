package dev.folio.core.pdf.export

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import dev.folio.core.common.Outcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Host check of the ADR-006 merge (PdfBox without BouncyCastle); PdfRenderer checks run in PdfBoxOverlayMergerInstrumentedTest. */
@RunWith(AndroidJUnit4::class)
class PdfBoxOverlayMergerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val merger = PdfBoxOverlayMerger(ApplicationProvider.getApplicationContext())

    @Test
    fun merge_overlayOnMiddlePage_onlyThatPageGetsTheForm() {
        val original = pdf("original.pdf", pages = 3)
        val overlay = pdf("overlay.pdf", pages = 1)
        val output = tmp.newFile("output.pdf")

        val result = merger.merge(original, overlay, intArrayOf(1), output)

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        PDDocument.load(output).use { merged ->
            assertThat(merged.numberOfPages).isEqualTo(3)
            assertThat(
                merged
                    .getPage(1)
                    .resources.xObjectNames
                    .toList(),
            ).hasSize(1)
            assertThat(
                merged
                    .getPage(0)
                    .resources.xObjectNames
                    .toList(),
            ).isEmpty()
            assertThat(
                merged
                    .getPage(2)
                    .resources.xObjectNames
                    .toList(),
            ).isEmpty()
            assertThat(merged.getPage(1).mediaBox.width).isEqualTo(PDRectangle.A4.width)
        }
    }

    @Test
    fun merge_pageCountMismatch_failsWithoutOutput() {
        val original = pdf("original.pdf", pages = 2)
        val overlay = pdf("overlay.pdf", pages = 2)
        val output = File(tmp.root, "output.pdf")

        val result = merger.merge(original, overlay, intArrayOf(0), output)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(output.exists()).isFalse()
        assertThat(File(tmp.root, "output.pdf.tmp").exists()).isFalse()
    }

    @Test
    fun merge_failureOverExistingOutput_leavesItUntouched() {
        val original = pdf("original.pdf", pages = 2)
        val overlay = pdf("overlay.pdf", pages = 2)
        val output = tmp.newFile("output.pdf").apply { writeText("previous export") }

        val result = merger.merge(original, overlay, intArrayOf(0), output)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(output.readText()).isEqualTo("previous export")
    }

    @Test
    fun merge_unwritableTarget_failsWithoutThrowing() {
        val original = pdf("original.pdf", pages = 1)
        val overlay = pdf("overlay.pdf", pages = 1)
        val output = File(tmp.root, "missing-dir/output.pdf")

        val result = merger.merge(original, overlay, intArrayOf(0), output)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(output.exists()).isFalse()
    }

    /** A4 pages with one filled rectangle each, written with PdfBox (PdfDocument needs the device). */
    private fun pdf(
        name: String,
        pages: Int,
    ): File {
        val file = tmp.newFile(name)
        PDDocument().use { document ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use {
                    it.addRect(50f + index * 10f, 50f, 100f, 100f)
                    it.fill()
                }
            }
            document.save(file)
        }
        return file
    }
}
