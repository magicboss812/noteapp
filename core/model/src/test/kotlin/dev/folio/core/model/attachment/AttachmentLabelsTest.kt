package dev.folio.core.model.attachment

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.AssetId
import dev.folio.core.model.Attachment
import dev.folio.core.model.ObjectId
import dev.folio.core.model.geometry.PointPt
import org.junit.Test

class AttachmentLabelsTest {
    @Test
    fun kindOf_mimeTypes_pickTheirIcon() {
        assertThat(AttachmentLabels.kindOf("application/pdf", "a")).isEqualTo(AttachmentKind.PDF)
        assertThat(AttachmentLabels.kindOf("image/png", "a.png")).isEqualTo(AttachmentKind.IMAGE)
        assertThat(AttachmentLabels.kindOf("audio/mpeg", "a.mp3")).isEqualTo(AttachmentKind.AUDIO)
        assertThat(AttachmentLabels.kindOf("video/mp4", "a.mp4")).isEqualTo(AttachmentKind.VIDEO)
        assertThat(AttachmentLabels.kindOf("text/plain; charset=utf-8", "a")).isEqualTo(AttachmentKind.TEXT)
        assertThat(
            AttachmentLabels.kindOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a"),
        ).isEqualTo(AttachmentKind.TEXT)
        assertThat(
            AttachmentLabels.kindOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "a"),
        ).isEqualTo(AttachmentKind.SHEET)
        assertThat(AttachmentLabels.kindOf("text/csv", "a.csv")).isEqualTo(AttachmentKind.SHEET)
        assertThat(AttachmentLabels.kindOf("application/zip", "a.zip")).isEqualTo(AttachmentKind.ARCHIVE)
    }

    @Test
    fun kindOf_genericMime_usesTheExtension() {
        assertThat(AttachmentLabels.kindOf("application/octet-stream", "Syllabus.PDF")).isEqualTo(AttachmentKind.PDF)
        assertThat(AttachmentLabels.kindOf("application/octet-stream", "notes.md")).isEqualTo(AttachmentKind.TEXT)
        assertThat(AttachmentLabels.kindOf("application/octet-stream", "data.tar.gz")).isEqualTo(AttachmentKind.ARCHIVE)
        assertThat(AttachmentLabels.kindOf("application/octet-stream", "model.stl")).isEqualTo(AttachmentKind.OTHER)
        assertThat(AttachmentLabels.kindOf("", "")).isEqualTo(AttachmentKind.OTHER)
    }

    @Test
    fun sizeLabel_bytes_binaryUnitsWithOneDecimalBelowTen() {
        assertThat(AttachmentLabels.sizeLabel(0)).isEmpty()
        assertThat(AttachmentLabels.sizeLabel(-5)).isEmpty()
        assertThat(AttachmentLabels.sizeLabel(512)).isEqualTo("512 B")
        assertThat(AttachmentLabels.sizeLabel(1024)).isEqualTo("1 KB")
        assertThat(AttachmentLabels.sizeLabel(1536)).isEqualTo("1.5 KB")
        assertThat(AttachmentLabels.sizeLabel(12 * 1024 + 300)).isEqualTo("12 KB")
        assertThat(AttachmentLabels.sizeLabel(3_565_158)).isEqualTo("3.4 MB")
        assertThat(AttachmentLabels.sizeLabel(1024L * 1024 - 1)).isEqualTo("1 MB")
        assertThat(AttachmentLabels.sizeLabel(5L * 1024 * 1024 * 1024)).isEqualTo("5 GB")
    }

    @Test
    fun attachment_bounds_areTheChipAtItsPosition() {
        val a = Attachment(ObjectId("a"), AssetId("x"), "f.pdf", "application/pdf", PointPt(10f, 20f), 99)
        assertThat(a.bounds.left).isEqualTo(10f)
        assertThat(a.bounds.top).isEqualTo(20f)
        assertThat(a.bounds.right).isEqualTo(10f + Attachment.CHIP_WIDTH_PT)
        assertThat(a.bounds.bottom).isEqualTo(20f + Attachment.CHIP_HEIGHT_PT)
    }
}
