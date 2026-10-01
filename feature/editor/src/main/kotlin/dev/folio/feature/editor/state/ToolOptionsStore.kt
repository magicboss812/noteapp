package dev.folio.feature.editor.state

import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.ink.erase.EraserMode
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Persists [ToolOptions] as JSON in the app settings (one key, written on every change). Missing fields
 * read as defaults and invalid or unreadable values fall back to defaults, so a settings file from an
 * older or newer build never blocks the editor.
 */
class ToolOptionsStore
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) {
        /** The stored options, defaults while nothing is stored. */
        val options: Flow<ToolOptions> = settings.read(KEY).map { it?.let(::decode) ?: ToolOptions() }

        /** Stores [options]. */
        suspend fun save(options: ToolOptions): Outcome<Unit> = settings.write(KEY, encode(options))

        internal companion object {
            const val KEY = "editor.toolOptions"
            private const val TAG = "ToolOptionsStore"
            private val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }

            fun encode(options: ToolOptions): String = json.encodeToString(ToolOptionsJson.serializer(), options.toJson())

            fun decode(text: String): ToolOptions =
                try {
                    json.decodeFromString(ToolOptionsJson.serializer(), text).toOptions()
                } catch (e: SerializationException) {
                    fallback(e)
                } catch (e: IllegalArgumentException) {
                    // Values that break an options invariant (sizes, indices, kinds).
                    fallback(e)
                }

            private fun fallback(e: Exception): ToolOptions {
                FolioLog.w(TAG, "stored tool options unreadable, using defaults", e)
                return ToolOptions()
            }
        }
    }

// Storage shape, decoupled from the in-memory types so they can change without breaking stored settings.

@Serializable
private data class SwatchesJson(
    val colors: List<Int>,
    val selected: Int = 0,
)

@Serializable
private data class WidthsJson(
    val widthsPt: List<Float>,
    val selected: Int = 1,
)

@Serializable
private data class PenJson(
    val kind: BrushKind = BrushKind.BALLPOINT,
    val widths: Map<BrushKind, WidthsJson> = emptyMap(),
    val swatches: SwatchesJson? = null,
    val pressureGamma: Float = 1f,
)

@Serializable
private data class HighlighterJson(
    val widths: WidthsJson? = null,
    val swatches: SwatchesJson? = null,
    val alwaysStraight: Boolean = false,
)

@Serializable
private data class EraserJson(
    val mode: EraserMode = EraserMode.STROKE,
    val radiusPt: Float = EraserOptions.DEFAULT.radiusPt,
    val highlighterOnly: Boolean = false,
)

@Serializable
private data class ToolOptionsJson(
    val version: Int = 1,
    val pen: PenJson = PenJson(),
    val highlighter: HighlighterJson = HighlighterJson(),
    val eraser: EraserJson = EraserJson(),
    val recentColors: List<Int> = emptyList(),
)

private fun Swatches.toJson() = SwatchesJson(colors, selected)

private fun SwatchesJson.toSwatches() = Swatches(colors.toImmutableList(), selected)

private fun WidthPresets.toJson() = WidthsJson(widthsPt, selected)

private fun WidthsJson.toPresets() = WidthPresets(widthsPt.map(BrushPresets::clampWidth).toImmutableList(), selected)

private fun ToolOptions.toJson() =
    ToolOptionsJson(
        pen = PenJson(pen.kind, pen.widths.mapValues { it.value.toJson() }, pen.swatches.toJson(), pen.pressureGamma),
        highlighter = HighlighterJson(highlighter.widths.toJson(), highlighter.swatches.toJson(), highlighter.alwaysStraight),
        eraser = EraserJson(eraser.mode, eraser.radiusPt, eraser.highlighterOnly),
        recentColors = recentColors,
    )

private fun ToolOptionsJson.toOptions(): ToolOptions {
    val defaults = ToolOptions()
    val penWidths = defaults.pen.widths + pen.widths.filterKeys { it in PEN_KINDS }.mapValues { it.value.toPresets() }
    return ToolOptions(
        pen =
            PenOptions(
                kind = pen.kind,
                widths = penWidths.toImmutableMap(),
                swatches = pen.swatches?.toSwatches() ?: defaults.pen.swatches,
            ).withPressureGamma(pen.pressureGamma),
        highlighter =
            HighlighterOptions(
                widths = highlighter.widths?.toPresets() ?: defaults.highlighter.widths,
                swatches = highlighter.swatches?.toSwatches() ?: defaults.highlighter.swatches,
                alwaysStraight = highlighter.alwaysStraight,
            ),
        eraser = EraserOptions(eraser.mode, eraser.radiusPt, eraser.highlighterOnly),
        recentColors = recentColors.take(ToolOptions.MAX_RECENT_COLORS).toImmutableList(),
    )
}
