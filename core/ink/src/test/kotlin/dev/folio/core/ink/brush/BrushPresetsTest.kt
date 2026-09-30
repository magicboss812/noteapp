package dev.folio.core.ink.brush

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import org.junit.Test

class BrushPresetsTest {
    @Test
    fun widthsPt_everyKind_matchesSpecTable() {
        assertThat(BrushPresets.widthsPt(BrushKind.BALLPOINT)).containsExactly(0.6f, 0.9f, 1.3f).inOrder()
        assertThat(BrushPresets.widthsPt(BrushKind.FOUNTAIN)).containsExactly(0.8f, 1.2f, 1.8f).inOrder()
        assertThat(BrushPresets.widthsPt(BrushKind.PENCIL)).containsExactly(0.8f, 1.2f, 2.0f).inOrder()
        assertThat(BrushPresets.widthsPt(BrushKind.MARKER)).containsExactly(1.5f, 2.5f, 4.0f).inOrder()
        assertThat(BrushPresets.widthsPt(BrushKind.HIGHLIGHTER)).containsExactly(8f, 12f, 18f).inOrder()
    }

    @Test
    fun palette_penAndHighlighter_matchSpecColors() {
        assertThat(BrushPresets.palette(BrushKind.FOUNTAIN).map(::hex))
            .containsExactly("FF1A1A1A", "FF2563EB", "FFDC2626", "FF16A34A", "FF7C3AED")
            .inOrder()
        assertThat(BrushPresets.palette(BrushKind.HIGHLIGHTER).map(::hex))
            .containsExactly("FFFDE047", "FF86EFAC", "FFF9A8D4", "FF93C5FD", "FFFDBA74")
            .inOrder()
    }

    @Test
    fun clampWidth_outsideCustomRangeOrNaN_limitedToRange() {
        assertThat(BrushPresets.clampWidth(0.1f)).isEqualTo(0.3f)
        assertThat(BrushPresets.clampWidth(45f)).isEqualTo(30f)
        assertThat(BrushPresets.clampWidth(Float.NaN)).isEqualTo(0.3f)
        assertThat(BrushPresets.clampWidth(2.5f)).isEqualTo(2.5f)
    }

    private fun hex(argb: Int): String = "%08X".format(argb)
}
