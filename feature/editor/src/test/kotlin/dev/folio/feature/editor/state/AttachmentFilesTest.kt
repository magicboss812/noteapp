package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.AssetId
import dev.folio.core.model.Attachment
import dev.folio.core.model.geometry.RectPt
import org.junit.Test
import kotlin.io.path.createTempDirectory

class AttachmentFilesTest {
    @Test
    fun safeName_separatorsAndControlChars_replaced_neverHidden() {
        assertThat(AttachmentFiles.safeName("../../etc/passwd")).isEqualTo("_.._etc_passwd")
        assertThat(AttachmentFiles.safeName("a\\b:c*?.pdf")).isEqualTo("a_b_c__.pdf")
        assertThat(AttachmentFiles.safeName("line\nbreak.txt")).isEqualTo("line_break.txt")
        assertThat(AttachmentFiles.safeName("..")).isEqualTo("attachment")
        assertThat(AttachmentFiles.safeName("   ")).isEqualTo("attachment")
        assertThat(AttachmentFiles.safeName(".hidden")).isEqualTo("hidden")
        assertThat(AttachmentFiles.safeName("Syllabus.pdf")).isEqualTo("Syllabus.pdf")
    }

    @Test
    fun safeName_longName_cutKeepingTheExtension() {
        val name = AttachmentFiles.safeName("x".repeat(300) + ".docx")
        assertThat(name.length).isEqualTo(120)
        assertThat(name).endsWith("x.docx")
    }

    @Test
    fun place_centeredInTheVisiblePart_keptOnThePage() {
        val inside = AttachmentFiles.place(RectPt(100f, 200f, 500f, 700f), 595f, 842f)
        assertThat(inside.x).isEqualTo(300f - Attachment.CHIP_WIDTH_PT / 2)
        assertThat(inside.y).isEqualTo(450f - Attachment.CHIP_HEIGHT_PT / 2)
        // Visible part near the page's right and bottom edges: the chip stays whole on the page.
        val edge = AttachmentFiles.place(RectPt(500f, 820f, 595f, 842f), 595f, 842f)
        assertThat(edge.x).isEqualTo(595f - Attachment.CHIP_WIDTH_PT)
        assertThat(edge.y).isEqualTo(842f - Attachment.CHIP_HEIGHT_PT)
    }

    @Test
    fun write_sameAssetTwice_overwritesOneFileUnderTheAssetDir() {
        val root = createTempDirectory("cache").toFile()
        val target = AttachmentTarget(AssetId("0123456789abcdef".repeat(4)), "a/b.pdf", "application/pdf")

        val first = AttachmentFiles.write(root, target, byteArrayOf(1, 2))
        val second = AttachmentFiles.write(root, target, byteArrayOf(3))

        assertThat(second).isEqualTo(first)
        assertThat(first.name).isEqualTo("a_b.pdf")
        assertThat(first.parentFile?.name).isEqualTo("0123456789abcdef")
        assertThat(first.readBytes()).isEqualTo(byteArrayOf(3))
        assertThat(first.parentFile?.list()?.toList()).containsExactly("a_b.pdf")
    }
}
