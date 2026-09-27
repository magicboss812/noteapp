package dev.folio.app.debug

import dev.folio.app.spikes.SpikeInkView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What `state` reports. Until navigation (P04/P05) exists the app has one screen, so routes only
 * record the request. Later phases replace these fields with reads from real app state.
 */
internal class DebugAppState {
    var screen: String = PLACEHOLDER
        private set
    var route: String = PLACEHOLDER
        private set
    var requestedDoc: String? = null
    val openDoc: String? = null
    val tool: String? = null
    val zoom: Float? = null

    /** Routes the app can show today; `spike-*` routes are P01 spike screens. */
    val knownRoutes: Set<String> = setOf(PLACEHOLDER, SpikeInkView.ROUTE)

    /** Called after every successful [navigate] with the new route; the hooks show the screen. */
    var onNavigate: (String) -> Unit = {}

    /** Returns false for an unknown route. */
    fun navigate(to: String): Boolean {
        if (to !in knownRoutes) return false
        route = to
        screen = to
        onNavigate(to)
        return true
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("screen", screen)
            put("route", route)
            put("doc", openDoc)
            put("requestedDoc", requestedDoc)
            put("tool", tool)
            put("zoom", zoom)
        }

    private companion object {
        const val PLACEHOLDER = "placeholder"
    }
}
