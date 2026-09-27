package dev.folio.app.spikes

import dev.folio.app.debug.DebugReply
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `debugcmd.sh spike-ink [stats|clear|immediate|frame|commit]` for P01-S1: reports counters and
 * refresh rates, clears the page, or switches the [Handoff] mode. [view] is null while the route is hidden.
 */
internal fun spikeInkCommand(
    view: SpikeInkView?,
    arg: String?,
): DebugReply {
    if (view == null) return DebugReply.error("route ${SpikeInkView.ROUTE} is not shown")
    when (arg) {
        null, "stats" -> Unit
        "clear" -> view.clear()
        else -> view.handoff = Handoff.parse(arg) ?: return DebugReply.error("spike-ink needs stats|clear|immediate|frame|commit")
    }
    return DebugReply.ok(
        buildJsonObject {
            put("handoff", view.handoff.name.lowercase())
            put("committed", view.committedCount)
            put("pendingWet", view.pendingWetCount)
            put("requestedHz", view.requestedHz)
            put("activeHz", view.activeHz())
        },
    )
}
