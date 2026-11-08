package dev.folio.core.format.manifest

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.TextAlign
import org.junit.Test

class FlowStyleJsonTest {
    private fun roundTrip(style: FlowStyle): FlowStyle {
        val text = FolioJson.encodeToString(FlowStyleJson.serializer(), ManifestMapper.flowStyleToJson(style, autoContinue = true))
        return ManifestMapper.flowStyleFromJson(FolioJson.decodeFromString(FlowStyleJson.serializer(), text))
    }

    private fun parse(text: String): FlowStyle =
        ManifestMapper.flowStyleFromJson(FolioJson.decodeFromString(FlowStyleJson.serializer(), text))

    @Test
    fun roundTrip_withBaselineLift_keepsIt() {
        val style = FlowStyle("Kalam", 0.6f, 1, TextAlign.CENTER, baselineLiftUnits = 0.25f)

        assertThat(roundTrip(style)).isEqualTo(style)
    }

    @Test
    fun roundTrip_withoutLift_isZero() {
        val style = FlowStyle("Inter", 0.45f, 0, TextAlign.START)

        assertThat(roundTrip(style)).isEqualTo(style)
        assertThat(roundTrip(style).baselineLiftUnits).isEqualTo(0f)
    }

    @Test
    fun parse_fileWithoutTheField_liftZero() {
        val style = parse("""{"fontFamily":"Inter","sizeRatio":0.5,"paragraphGapLines":0,"align":"start","autoContinue":true}""")

        assertThat(style).isEqualTo(FlowStyle("Inter", 0.5f, 0, TextAlign.START, baselineLiftUnits = 0f))
    }

    @Test
    fun parse_liftOutOfRangeOrOffStep_clampedAndRounded() {
        assertThat(parse("""{"baselineLiftUnits":0.9}""").baselineLiftUnits).isEqualTo(FlowStyle.MAX_BASELINE_LIFT)
        assertThat(parse("""{"baselineLiftUnits":-1}""").baselineLiftUnits).isEqualTo(0f)
        assertThat(parse("""{"baselineLiftUnits":0.12}""").baselineLiftUnits).isEqualTo(0.1f)
    }
}
