package dev.folio.core.text.fonts

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import org.junit.Test
import java.io.File

class FontFileInfoTest {
    @Test
    fun parse_bundledVariableFont_readsFamily() {
        val info = parse("inter_var.ttf")

        assertThat(info.familyName).isEqualTo("Inter")
        assertThat(info.bold).isFalse()
        assertThat(info.italic).isFalse()
        assertThat(info.extension).isEqualTo("ttf")
    }

    @Test
    fun parse_staticBoldItalic_readsFace() {
        val info = parse("ibm_plex_mono_bold_italic.ttf")

        assertThat(info.familyName).isEqualTo("IBM Plex Mono")
        assertThat(info.bold).isTrue()
        assertThat(info.italic).isTrue()
    }

    @Test
    fun parse_italicFile_italicOnly() {
        val info = parse("lora_italic_var.ttf")

        assertThat(info.familyName).isEqualTo("Lora")
        assertThat(info.italic).isTrue()
    }

    @Test
    fun parse_garbageOrTruncated_fails() {
        val inter = File(FONT_DIR, "inter_var.ttf").readBytes()

        assertThat(FontFileInfo.parse(ByteArray(0))).isInstanceOf(Outcome.Failure::class.java)
        assertThat(FontFileInfo.parse("not a font at all".toByteArray())).isInstanceOf(Outcome.Failure::class.java)
        assertThat(FontFileInfo.parse(inter.copyOf(200))).isInstanceOf(Outcome.Failure::class.java)
        assertThat(FontFileInfo.parse(inter.copyOf(inter.size / 2).also { it[4] = 0x7F })).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun parse_fontCollection_rejected() {
        val ttc = byteArrayOf(0x74, 0x74, 0x63, 0x66) + ByteArray(64)

        assertThat(FontFileInfo.parse(ttc)).isInstanceOf(Outcome.Failure::class.java)
    }

    private fun parse(name: String): FontFileInfo {
        val result = FontFileInfo.parse(File(FONT_DIR, name).readBytes())
        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        return (result as Outcome.Success).value
    }

    private companion object {
        /** Unit tests run with the module directory as working directory. */
        val FONT_DIR = File("src/main/res/font")
    }
}
