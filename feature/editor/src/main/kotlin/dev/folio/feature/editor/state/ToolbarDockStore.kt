package dev.folio.feature.editor.state

import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Persists [ToolbarDocks] as JSON in the app settings (one key). Missing fields read as defaults and
 * unreadable values fall back to defaults, like [ToolOptionsStore].
 */
class ToolbarDockStore
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) {
        /** The stored placements, defaults while nothing is stored. */
        val docks: Flow<ToolbarDocks> = settings.read(KEY).map { it?.let(::decode) ?: ToolbarDocks() }

        /** Stores [docks]. */
        suspend fun save(docks: ToolbarDocks): Outcome<Unit> = settings.write(KEY, encode(docks))

        internal companion object {
            const val KEY = "editor.toolbarDocks"
            private const val TAG = "ToolbarDockStore"
            private val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }

            fun encode(docks: ToolbarDocks): String = json.encodeToString(DocksJson.serializer(), docks.toJson())

            fun decode(text: String): ToolbarDocks =
                try {
                    json.decodeFromString(DocksJson.serializer(), text).toDocks()
                } catch (e: SerializationException) {
                    fallback(e)
                } catch (e: IllegalArgumentException) {
                    // A float position outside 0..1.
                    fallback(e)
                }

            private fun fallback(e: Exception): ToolbarDocks {
                FolioLog.w(TAG, "stored toolbar placement unreadable, using defaults", e)
                return ToolbarDocks()
            }
        }
    }

@Serializable
private data class PlacementJson(
    val mode: DockMode = DockMode.TOP,
    val floatX: Float = ToolbarPlacement.DEFAULT_FLOAT_X,
    val floatY: Float = ToolbarPlacement.DEFAULT_FLOAT_Y,
    val collapsed: Boolean = false,
)

@Serializable
private data class DocksJson(
    val version: Int = 1,
    val handedness: Handedness = Handedness.RIGHT,
    val landscape: PlacementJson = PlacementJson(),
    val portrait: PlacementJson = PlacementJson(),
)

private fun ToolbarPlacement.toJson() = PlacementJson(mode, floatX, floatY, collapsed)

private fun PlacementJson.toPlacement() = ToolbarPlacement(mode, floatX, floatY, collapsed)

private fun ToolbarDocks.toJson() = DocksJson(handedness = handedness, landscape = landscape.toJson(), portrait = portrait.toJson())

private fun DocksJson.toDocks() = ToolbarDocks(handedness, landscape.toPlacement(), portrait.toPlacement())
