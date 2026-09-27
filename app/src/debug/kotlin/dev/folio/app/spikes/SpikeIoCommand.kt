package dev.folio.app.spikes

import dev.folio.app.debug.DebugReply

/**
 * `debugcmd.sh spike-io [stats|prepare|pack|pack-slow|verify|mark]` for P01-S6. Actions run in the
 * background; poll `stats` until `state` ends with `done`. [view] is null while the route is hidden.
 */
internal fun spikeIoCommand(
    view: SpikeIoView?,
    arg: String?,
): DebugReply {
    if (view == null) return DebugReply.error("route ${SpikeIoView.ROUTE} is not shown")
    when (arg) {
        null, "stats" -> Unit
        "prepare" -> view.prepare()
        "pack" -> view.pack(slow = false)
        "pack-slow" -> view.pack(slow = true)
        "verify" -> view.verify()
        "mark" -> view.mark()
        else -> return DebugReply.error("spike-io needs stats|prepare|pack|pack-slow|verify|mark")
    }
    return DebugReply.ok(view.stats())
}
