package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.ink.erase.EraserMode
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolOptionsStoreTest {
    private val settings = FakeSettingsStore()
    private val store = ToolOptionsStore(settings)

    private fun edited(): ToolOptions {
        val pen =
            PenOptions(kind = BrushKind.PENCIL)
                .let { it.withKindWidths(it.kindWidths.set(0, 2.2f).select(0)) }
                .let { it.copy(swatches = it.swatches.add(0xFF0A0B0C.toInt())) }
                .withPressureGamma(0.7f)
        val highlighter =
            HighlighterOptions().let {
                it.copy(widths = it.widths.select(2), swatches = it.swatches.replace(1, 0xFFABCDEF.toInt()), alwaysStraight = true)
            }
        return ToolOptions(pen, highlighter, EraserOptions(EraserMode.PARTIAL, EraserOptions.SIZES_PT[2], highlighterOnly = true))
            .withRecentColor(0xFF0A0B0C.toInt())
    }

    @Test
    fun options_nothingStored_defaults() =
        runTest {
            assertThat(store.options.first()).isEqualTo(ToolOptions())
        }

    @Test
    fun save_thenRead_roundTripsEveryToolsOptions() =
        runTest {
            val options = edited()

            store.save(options)

            assertThat(store.options.first()).isEqualTo(options)
            assertThat(ToolOptionsStore(settings).options.first()).isEqualTo(options)
        }

    @Test
    fun decode_missingFields_defaultsForThem() {
        val decoded = ToolOptionsStore.decode("""{"pen":{"kind":"MARKER"},"futureTool":{"x":1}}""")

        assertThat(decoded).isEqualTo(ToolOptions(pen = PenOptions(kind = BrushKind.MARKER)))
    }

    @Test
    fun decode_brokenJsonOrInvariant_defaults() {
        assertThat(ToolOptionsStore.decode("{not json")).isEqualTo(ToolOptions())
        assertThat(ToolOptionsStore.decode("""{"pen":{"kind":"HIGHLIGHTER"}}""")).isEqualTo(ToolOptions())
        assertThat(ToolOptionsStore.decode("""{"pen":{"swatches":{"colors":[],"selected":0}}}""")).isEqualTo(ToolOptions())
        assertThat(ToolOptionsStore.decode("""{"eraser":{"radiusPt":-1}}""")).isEqualTo(ToolOptions())
    }

    @Test
    fun decode_outOfRangeWidthsAndGamma_clamped() {
        val decoded =
            ToolOptionsStore.decode(
                """{"pen":{"widths":{"BALLPOINT":{"widthsPt":[0.01,1,500],"selected":2}},"pressureGamma":9}}""",
            )

        assertThat(decoded.pen.kindWidths.widthsPt)
            .containsExactly(BrushPresets.MIN_WIDTH_PT, 1f, BrushPresets.MAX_WIDTH_PT)
            .inOrder()
        assertThat(decoded.pen.pressureGamma).isEqualTo(2f)
    }
}
