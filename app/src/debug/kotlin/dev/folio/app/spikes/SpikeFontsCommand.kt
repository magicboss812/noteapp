package dev.folio.app.spikes

import dev.folio.app.debug.DebugReply
import dev.folio.core.text.layout.BaselineMethod
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Parsed `spike-fonts` argument: a zoom, a baseline method, or null for stats. */
internal sealed interface SpikeFontsArg {
    data object Stats : SpikeFontsArg

    data class Zoom(
        val zoom: Float,
    ) : SpikeFontsArg

    data class Method(
        val method: BaselineMethod,
    ) : SpikeFontsArg

    companion object {
        /** Null when [arg] is neither `stats`, `zoom=<0.25..8>`, `grid` nor `descent`. */
        fun parse(arg: String?): SpikeFontsArg? =
            when {
                arg == null || arg == "stats" -> {
                    Stats
                }

                arg == "grid" -> {
                    Method(BaselineMethod.GRID_PITCH)
                }

                arg == "descent" -> {
                    Method(BaselineMethod.FONT_DESCENT)
                }

                arg.startsWith("zoom=") -> {
                    arg
                        .removePrefix("zoom=")
                        .toFloatOrNull()
                        ?.takeIf { it in SpikeFontsView.MIN_ZOOM..SpikeFontsView.MAX_ZOOM }
                        ?.let(::Zoom)
                }

                else -> {
                    null
                }
            }
    }
}

/**
 * `debugcmd.sh spike-fonts [stats|zoom=<z>|grid|descent]` for P01-S3: reports zoom, method and
 * layout time, jumps to a zoom, or switches the baseline method. [view] is null while the route is hidden.
 */
internal fun spikeFontsCommand(
    view: SpikeFontsView?,
    arg: String?,
): DebugReply {
    if (view == null) return DebugReply.error("route ${SpikeFontsView.ROUTE} is not shown")
    when (val parsed = SpikeFontsArg.parse(arg) ?: return DebugReply.error("spike-fonts needs stats|zoom=<0.25..8>|grid|descent")) {
        SpikeFontsArg.Stats -> Unit
        is SpikeFontsArg.Zoom -> view.applyZoom(parsed.zoom)
        is SpikeFontsArg.Method -> view.switchMethod(parsed.method)
    }
    return DebugReply.ok(
        buildJsonObject {
            put("method", view.method.name)
            put("zoom", view.zoom)
            put("scale", view.scale)
            put("layoutMs", view.layoutMs)
        },
    )
}
