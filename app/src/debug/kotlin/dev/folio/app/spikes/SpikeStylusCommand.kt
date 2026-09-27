package dev.folio.app.spikes

import dev.folio.app.debug.DebugReply

/**
 * `debugcmd.sh spike-stylus [stats|reset]` for P01-S7: reports the probe snapshot (also written to
 * `Folio-Debug/probe/stylus.json`) or clears the counters. [view] is null while the route is hidden.
 */
internal fun spikeStylusCommand(
    view: SpikeStylusView?,
    arg: String?,
): DebugReply {
    if (view == null) return DebugReply.error("route ${SpikeStylusView.ROUTE} is not shown")
    when (arg) {
        null, "stats" -> Unit
        "reset" -> view.reset()
        else -> return DebugReply.error("spike-stylus needs stats|reset")
    }
    return DebugReply.ok(view.snapshot())
}
