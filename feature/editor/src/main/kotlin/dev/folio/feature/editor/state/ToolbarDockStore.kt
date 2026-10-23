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
 * unreadable values fall back to defaults, like [ToolOptionsStore]. Version 1 placements docked TOP (the
 * old default) read as BOTTOM, the new default (A-037); version 2 keeps TOP.
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

// Version 2 (P06-T12): BOTTOM dock and the default moved from TOP to BOTTOM.
private const val DOCKS_VERSION = 2

@Serializable
private data class PlacementJson(
    val mode: DockMode = DockMode.BOTTOM,
    val floatX: Float = ToolbarPlacement.DEFAULT_FLOAT_X,
    val floatY: Float = ToolbarPlacement.DEFAULT_FLOAT_Y,
    val collapsed: Boolean = false,
)

@Serializable
private data class DocksJson(
    // Version 1 wrote no newer fields; a missing version is 1.
    val version: Int = 1,
    val handedness: Handedness = Handedness.RIGHT,
    val landscape: PlacementJson = PlacementJson(),
    val portrait: PlacementJson = PlacementJson(),
    val optionsHidden: Boolean = false,
)

private fun ToolbarPlacement.toJson() = PlacementJson(mode, floatX, floatY, collapsed)

private fun PlacementJson.toPlacement(version: Int): ToolbarPlacement {
    val migrated = if (version < DOCKS_VERSION && mode == DockMode.TOP) DockMode.BOTTOM else mode
    return ToolbarPlacement(migrated, floatX, floatY, collapsed)
}

private fun ToolbarDocks.toJson() = DocksJson(DOCKS_VERSION, handedness, landscape.toJson(), portrait.toJson(), optionsHidden)

private fun DocksJson.toDocks() = ToolbarDocks(handedness, landscape.toPlacement(version), portrait.toPlacement(version), optionsHidden)
