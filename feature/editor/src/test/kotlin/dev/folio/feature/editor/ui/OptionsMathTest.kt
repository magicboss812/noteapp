package dev.folio.feature.editor.ui

import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.brush.BrushPresets
import org.junit.Test

class OptionsMathTest {
    @Test
    fun hsv_primariesAndGray_matchKnownValues() {
        assertThat(Hsv(0f, 1f, 1f).toArgb()).isEqualTo(0xFFFF0000.toInt())
        assertThat(Hsv(120f, 1f, 1f).toArgb()).isEqualTo(0xFF00FF00.toInt())
        assertThat(Hsv(240f, 1f, 0.5f).toArgb()).isEqualTo(0xFF000080.toInt())
        assertThat(Hsv(300f, 0f, 1f).toArgb()).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(Hsv.of(0xFF808080.toInt())).isEqualTo(Hsv(0f, 0f, 128 / 255f))
    }

    @Test
    fun hsv_roundTrip_everyPaletteColorAndHueEdge() {
        val colors = BrushPresets.PEN_PALETTE + BrushPresets.HIGHLIGHTER_PALETTE + 0xFFFF00FF.toInt() + 0xFF000000.toInt()

        colors.forEach { argb -> assertThat(Hsv.of(argb).toArgb()).isEqualTo(argb) }
    }

    @Test
    fun hex_formatParseAndInputFilter() {
        assertThat(hexOf(0xFF0A0B0C.toInt())).isEqualTo("0A0B0C")
        assertThat(parseHex("#2563eb")).isEqualTo(0xFF2563EB.toInt())
        assertThat(parseHex("2563E")).isNull()
        assertThat(parseHex("GG63EB")).isNull()
        assertThat(hexInput("#25 63eb99")).isEqualTo("2563EB")
    }

    @Test
    fun widthScale_endsAndRoundTrip_glyphGrowsWithWidth() {
        assertThat(WidthScale.fraction(BrushPresets.MIN_WIDTH_PT)).isEqualTo(0f)
        assertThat(WidthScale.fraction(99f)).isWithin(1e-6f).of(1f)
        assertThat(WidthScale.widthPt(1f)).isEqualTo(BrushPresets.MAX_WIDTH_PT)
        listOf(0.6f, 1.3f, 8f, 18f).forEach { assertThat(WidthScale.widthPt(WidthScale.fraction(it))).isWithin(1e-4f).of(it) }
    }

    @Test
    fun widthGlyph_proportionalToMediumPreset_clamped() {
        assertThat(WidthScale.glyph(12f, 12f)).isEqualTo(WidthScale.glyph(0.9f, 0.9f))
        assertThat(WidthScale.glyph(18f, 12f)).isGreaterThan(WidthScale.glyph(12f, 12f))
        assertThat(WidthScale.glyph(8f, 12f)).isLessThan(WidthScale.glyph(12f, 12f))
        assertThat(WidthScale.glyph(30f, 0.9f)).isEqualTo(WidthScale.glyph(99f, 0.9f))
    }
}
