package dev.folio.app.spikes

import dev.folio.app.debug.DebugReply
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Parsed `zoom-anim from,to,durationMs` argument. */
internal data class ZoomAnim(
    val from: Float,
    val to: Float,
    val durationMs: Long,
) {
    companion object {
        private const val PARTS = 3
        private const val MAX_DURATION_MS = 10_000L

        /** Null unless [arg] is three comma-separated numbers with zooms in range and 1..10000 ms. */
        fun parse(arg: String?): ZoomAnim? {
            val parts = arg?.split(',')?.takeIf { it.size == PARTS } ?: return null
            val zoomRange = SpikeTilesView.MIN_ZOOM..SpikeTilesView.MAX_ZOOM
            val from = parts[0].toFloatOrNull()?.takeIf { it in zoomRange }
            val to = parts[1].toFloatOrNull()?.takeIf { it in zoomRange }
            val durationMs = parts[2].toLongOrNull()?.takeIf { it in 1..MAX_DURATION_MS }
            return if (from != null && to != null && durationMs != null) ZoomAnim(from, to, durationMs) else null
        }
    }
}

/**
 * `debugcmd.sh spike-tiles [stats|a|b|zoom=<z>|verify]` for P01-S2: reports viewport and cache
 * counters (and the last verify result), switches the tile strategy, jumps to a zoom, or starts a
 * pixel comparison against a direct render. [view] is null while the route is hidden.
 */
internal fun spikeTilesCommand(
    view: SpikeTilesView?,
    arg: String?,
): DebugReply {
    if (view == null) return DebugReply.error("route ${SpikeTilesView.ROUTE} is not shown")
    val zoom = arg?.removePrefix("zoom=")?.takeIf { arg.startsWith("zoom=") }
    when {
        arg == null || arg == "stats" -> Unit
        arg == "verify" -> view.verify()
        zoom != null -> view.jumpToZoom(zoom.toFloatOrNull() ?: return DebugReply.error("zoom needs a number"))
        else -> view.switchStrategy(TileStrategy.parse(arg) ?: return DebugReply.error("spike-tiles needs stats|a|b|zoom=<z>|verify"))
    }
    return DebugReply.ok(
        buildJsonObject {
            put("strategy", view.strategy.name)
            put("strokes", view.strokeCount)
            put("seedMs", view.seedMs)
            put("zoom", view.zoom)
            put("scale", view.scale)
            put("bucket", view.bucket)
            put("tiles", view.tileCount)
            put("pending", view.pendingCount)
            put("pooled", view.pooledCount)
            put("requestedHz", view.requestedHz)
            put("activeHz", view.display?.refreshRate)
            view.lastVerify?.let { put("verify", it) }
        },
    )
}

/** `debugcmd.sh zoom-anim from,to,durationMs`: animates zoom on the tile spike screen (P01-S2). */
internal fun zoomAnimCommand(
    view: SpikeTilesView?,
    arg: String?,
): DebugReply {
    if (view == null) return DebugReply.error("route ${SpikeTilesView.ROUTE} is not shown")
    val anim = ZoomAnim.parse(arg) ?: return DebugReply.error("zoom-anim needs from,to,durationMs (zoom 0.25..8, 1..10000 ms)")
    view.animateZoom(anim.from, anim.to, anim.durationMs)
    return DebugReply.ok(
        buildJsonObject {
            put("from", anim.from)
            put("to", anim.to)
            put("durationMs", anim.durationMs)
        },
    )
}
