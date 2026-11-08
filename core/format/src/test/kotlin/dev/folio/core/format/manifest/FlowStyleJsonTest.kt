package dev.folio.core.format.manifest

import com.google.common.truth.Truth.assertThat
import dev.folio.core.format.codec.CorruptDataException
import dev.folio.core.model.Borders
import dev.folio.core.model.FlowBox
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.TextAlign
import dev.folio.core.testing.TestData
import org.junit.Assert.assertThrows
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
    fun roundTrip_everyBoxFieldAndColor_kept() {
        val style = FlowStyle("Inter", 0.45f, 0, TextAlign.END, textArgb = 0xFF1E5AA8.toInt())
        val box =
            FlowBox(
                anchorXPt = 140.5f,
                autoWidth = true,
                fill = true,
                custom = Borders(130.5f, 300f),
                fillBorders = Borders(62f, 550f),
                centerSaved = Borders(100f, 260f),
                offsetPt = -4.5f,
            )
        val json =
            FolioJson.decodeFromString(
                FlowStyleJson.serializer(),
                FolioJson.encodeToString(FlowStyleJson.serializer(), ManifestMapper.flowStyleToJson(style, true, box)),
            )

        assertThat(ManifestMapper.flowStyleFromJson(json)).isEqualTo(style)
        assertThat(ManifestMapper.flowBoxFromJson(json)).isEqualTo(box)
    }

    @Test
    fun golden_flowStyleWithBox_decodesEveryField() {
        val json = FolioJson.decodeFromString(FlowStyleJson.serializer(), TestData.file("format/v1/flow-style-box.json").readText())

        assertThat(ManifestMapper.flowStyleFromJson(json))
            .isEqualTo(FlowStyle("Kalam", 0.6f, 1, TextAlign.END, baselineLiftUnits = 0.2f, textArgb = 0xFF1E5AA8.toInt()))
        assertThat(ManifestMapper.flowBoxFromJson(json))
            .isEqualTo(
                FlowBox(140.5f, autoWidth = true, fill = false, Borders(130.5f, 300f), Borders(62f, 550f), Borders(100f, 260f), -4.5f),
            )
    }

    @Test
    fun parse_fileWithoutBox_bodyFlowFillsOthersFixed() {
        val body = FolioJson.decodeFromString(FlowStyleJson.serializer(), """{"autoContinue":true}""")
        val box = FolioJson.decodeFromString(FlowStyleJson.serializer(), """{"autoContinue":false}""")

        assertThat(ManifestMapper.flowBoxFromJson(body)).isEqualTo(FlowBox.FILL)
        assertThat(ManifestMapper.flowBoxFromJson(box)).isEqualTo(FlowBox.FIXED)
        assertThat(parse("""{"align":"start"}""").textArgb).isNull()
    }

    @Test
    fun parse_badBoxValues_corrupt() {
        val bad =
            listOf(
                """{"box":{"custom":[300.0,100.0]}}""",
                """{"box":{"fillBorders":[1.0]}}""",
                """{"box":{"anchorXPt":1e9}}""",
                """{"box":{"offsetPt":2e7}}""",
            )
        for (text in bad) {
            val json = FolioJson.decodeFromString(FlowStyleJson.serializer(), text)
            assertThrows(CorruptDataException::class.java) { ManifestMapper.flowBoxFromJson(json) }
        }
        assertThrows(CorruptDataException::class.java) { parse("""{"textArgb":"blue"}""") }
    }

    @Test
    fun parse_liftOutOfRangeOrOffStep_clampedAndRounded() {
        assertThat(parse("""{"baselineLiftUnits":0.9}""").baselineLiftUnits).isEqualTo(FlowStyle.MAX_BASELINE_LIFT)
        assertThat(parse("""{"baselineLiftUnits":-1}""").baselineLiftUnits).isEqualTo(0f)
        assertThat(parse("""{"baselineLiftUnits":0.12}""").baselineLiftUnits).isEqualTo(0.1f)
    }
}
