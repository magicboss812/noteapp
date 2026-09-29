package dev.folio.app.debug

import dev.folio.app.spikes.SpikeFontsView
import dev.folio.app.spikes.SpikeInkView
import dev.folio.app.spikes.SpikeStylusView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What `state` reports. Until navigation (P04/P05) exists the app has one base screen (the library
 * entry: storage onboarding or library placeholder); routes only record the request. Later phases
 * replace these fields with reads from real app state.
 */
internal class DebugAppState(
    /** Library access facts (`library` key), null when unavailable. */
    private val library: () -> JsonObject? = { null },
    /** Screen shown on the base route: "onboarding" or "library". */
    private val baseScreen: () -> String = { LIBRARY },
    /** Library path of the document shown on the canvas route, if any. */
    private val openDocPath: () -> String? = { null },
    /** Zoom (times fit-width) of the canvas, if laid out. */
    private val canvasZoom: () -> Float? = { null },
    /** Canvas facts (`canvas` key), null when no document is open. */
    private val canvas: () -> JsonObject? = { null },
) {
    var route: String = LIBRARY
        private set
    val screen: String get() = if (route == LIBRARY) baseScreen() else route
    var requestedDoc: String? = null
    val openDoc: String? get() = openDocPath()
    val tool: String? = null
    val zoom: Float? get() = canvasZoom()

    /** Routes the app can show today; `spike-*` routes are P01 probe screens (D-002). */
    val knownRoutes: Set<String> = setOf(LIBRARY, CANVAS, SpikeInkView.ROUTE, SpikeFontsView.ROUTE, SpikeStylusView.ROUTE)

    /** Called after every successful [navigate] with the new route; the hooks show the screen. */
    var onNavigate: (String) -> Unit = {}

    /** Returns false for an unknown route. */
    fun navigate(to: String): Boolean {
        if (to !in knownRoutes) return false
        route = to
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
            library()?.let { put("library", it) }
            canvas()?.let { put("canvas", it) }
        }

    companion object {
        /** Base route: the library entry screen. */
        const val LIBRARY = "library"

        /** The canvas host showing the document opened by `open` (until the P04 editor route). */
        const val CANVAS = "canvas"
    }
}
