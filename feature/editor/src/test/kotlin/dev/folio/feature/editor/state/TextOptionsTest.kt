package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.TextAlign
import dev.folio.core.text.fonts.BodySize
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.fonts.TextSizing
import org.junit.Test

class TextOptionsTest {
    @Test
    fun defaults_defaultFamilyMediumPreset() {
        val text = TextOptions()

        assertThat(text.fontFamily).isEqualTo(FontRegistry.DEFAULT_FAMILY)
        assertThat(text.preset).isEqualTo(BodySize.M)
        assertThat(text.bodyLines).isEqualTo(1)
    }

    @Test
    fun withBodyCap_presetValue_matchesPreset_freeValueHasNone() {
        BodySize.entries.forEach { assertThat(TextOptions().withBodyCap(it.capFraction).preset).isEqualTo(it) }
        assertThat(TextOptions().withBodyCap(0.7f).preset).isNull()
    }

    @Test
    fun withBodyCap_outOfRangeOrBetweenSteps_clampedAndRounded() {
        assertThat(TextOptions().withBodyCap(0f).bodyCap).isEqualTo(TextSizing.MIN_BODY_CAP)
        assertThat(TextOptions().withBodyCap(9f).bodyCap).isEqualTo(TextSizing.MAX_BODY_CAP)
        assertThat(TextOptions().withBodyCap(0.734f).bodyCap).isEqualTo(0.73f)
        // Every slider stop stays inside the range (no float drift past the ends).
        (30..180).forEach { TextOptions().withBodyCap(it / 100f) }
    }

    @Test
    fun bodyLines_growWithTheFreeSize() {
        assertThat(TextOptions().withBodyCap(BodySize.L.capFraction).bodyLines).isEqualTo(1)
        assertThat(TextOptions().withBodyCap(1.2f).bodyLines).isEqualTo(2)
        assertThat(TextOptions().withBodyCap(TextSizing.MAX_BODY_CAP).bodyLines).isAtLeast(2)
    }

    @Test
    fun flowStyle_carriesFamilyAndSize() {
        val text = TextOptions("Kalam").withBodyCap(0.6f)

        assertThat(text.flowStyle()).isEqualTo(FlowStyle("Kalam", 0.6f, 0, TextAlign.START))
    }
}
