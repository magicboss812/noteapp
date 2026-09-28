package dev.folio.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PageSpecTest {
    @Test
    fun paperSize_isoSizes_matchPdfPoints() {
        assertThat(PaperSize.A4.widthPt).isWithin(EPS).of(595.2756f)
        assertThat(PaperSize.A4.heightPt).isWithin(EPS).of(841.8898f)
        assertThat(PaperSize.A5.widthPt).isWithin(EPS).of(419.5276f)
        assertThat(PaperSize.A5.heightPt).isWithin(EPS).of(595.2756f)
        assertThat(PaperSize.A3.widthPt).isWithin(EPS).of(841.8898f)
        assertThat(PaperSize.A3.heightPt).isWithin(EPS).of(1190.5512f)
    }

    @Test
    fun paperSize_a4InMillimeters_is210x297() {
        assertThat(PaperSize.A4.widthPt / 72f * 25.4f).isWithin(0.001f).of(210f)
        assertThat(PaperSize.A4.heightPt / 72f * 25.4f).isWithin(0.001f).of(297f)
    }

    @Test
    fun fixed_landscape_swapsWidthAndHeight() {
        for (size in PaperSize.entries) {
            val portrait = PageSpec.Fixed(size, Orientation.PORTRAIT)
            val landscape = PageSpec.Fixed(size, Orientation.LANDSCAPE)
            assertThat(portrait.widthPt).isEqualTo(size.widthPt)
            assertThat(portrait.heightPt).isEqualTo(size.heightPt)
            assertThat(landscape.widthPt).isEqualTo(size.heightPt)
            assertThat(landscape.heightPt).isEqualTo(size.widthPt)
        }
    }

    @Test
    fun infinite_frame_isOriginFrame() {
        val spec = PageSpec.Infinite(PageSpec.Fixed(PaperSize.A5, Orientation.LANDSCAPE))
        assertThat(spec.widthPt).isEqualTo(PaperSize.A5.heightPt)
        assertThat(spec.heightPt).isEqualTo(PaperSize.A5.widthPt)
        assertThat(PageSpec.Infinite(PageSpec.Custom(100f, 50f)).widthPt).isEqualTo(100f)
    }

    @Test
    fun infinite_nestedInfinite_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PageSpec.Infinite(PageSpec.Infinite(PageSpec.Custom(1f, 1f)))
        }
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
