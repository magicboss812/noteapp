package dev.folio.app.debug

import dev.folio.app.spikes.SpikeFontsView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What `state` reports. The app navigator shows the library entry (storage onboarding or library) or
 * the editor opened by `open`; a debug-only screen such as `spike-fonts` may cover both.
 */
internal class DebugAppState(
    /** Library access facts (`library` key), null when unavailable. */
    private val library: () -> JsonObject? = { null },
    /** Screen shown on the library route: "onboarding" or "library". */
    private val baseScreen: () -> String = { LIBRARY },
    /** True while the navigator shows an editor. */
    private val editorShown: () -> Boolean = { false },
    /** Library path of the document shown in the editor, if any. */
    private val openDocPath: () -> String? = { null },
    /** Zoom (times fit-width) of the canvas, if laid out. */
    private val canvasZoom: () -> Float? = { null },
    /** Canvas facts (`canvas` key), null when no document is open. */
    private val canvas: () -> JsonObject? = { null },
) {
    /** Debug-only screen covering the app, if any. */
    var overlay: String? = null
        private set
    val route: String get() = overlay ?: if (editorShown()) EDITOR else LIBRARY
    val screen: String get() = if (route == LIBRARY) baseScreen() else route
    var requestedDoc: String? = null
    val openDoc: String? get() = openDocPath()
    val tool: String? = null
    val zoom: Float? get() = canvasZoom()

    /**
     * Routes `route` accepts: `library` pops to the library and removes any overlay, `spike-fonts` is a
     * P01 probe screen (D-002). The editor is reached through `open`.
     */
    val knownRoutes: Set<String> = setOf(LIBRARY, SpikeFontsView.ROUTE)

    /** Called after every successful [navigate] with the new route; the hooks show the screen. */
    var onNavigate: (String) -> Unit = {}

    /** Returns false for an unknown route. */
    fun navigate(to: String): Boolean {
        if (to !in knownRoutes) return false
        overlay = to.takeUnless { it == LIBRARY }
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

        /** The editor showing the document opened by `open` (P04-T02). */
        const val EDITOR = "editor"
    }
}
