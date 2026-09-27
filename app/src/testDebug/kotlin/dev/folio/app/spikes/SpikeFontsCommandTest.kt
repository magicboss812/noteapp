package dev.folio.app.spikes

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.layout.BaselineMethod
import org.junit.Test

class SpikeFontsCommandTest {
    @Test
    fun spikeFontsCommand_routeHidden_notOk() {
        val reply = spikeFontsCommand(view = null, arg = "zoom=2")

        assertThat(reply.ok).isFalse()
        assertThat(reply.json.toString()).contains("spike-fonts is not shown")
    }

    @Test
    fun parse_validArgs_mapsToVariants() {
        assertThat(SpikeFontsArg.parse(null)).isEqualTo(SpikeFontsArg.Stats)
        assertThat(SpikeFontsArg.parse("stats")).isEqualTo(SpikeFontsArg.Stats)
        assertThat(SpikeFontsArg.parse("zoom=4")).isEqualTo(SpikeFontsArg.Zoom(4f))
        assertThat(SpikeFontsArg.parse("grid")).isEqualTo(SpikeFontsArg.Method(BaselineMethod.GRID_PITCH))
        assertThat(SpikeFontsArg.parse("descent")).isEqualTo(SpikeFontsArg.Method(BaselineMethod.FONT_DESCENT))
    }

    @Test
    fun parse_badArgs_null() {
        assertThat(SpikeFontsArg.parse("zoom=9")).isNull()
        assertThat(SpikeFontsArg.parse("zoom=x")).isNull()
        assertThat(SpikeFontsArg.parse("bold")).isNull()
    }
}
