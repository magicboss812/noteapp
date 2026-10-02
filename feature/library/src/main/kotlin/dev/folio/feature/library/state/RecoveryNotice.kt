package dev.folio.feature.library.state

import dev.folio.core.storage.work.RecoveryEvent

/** A recovery snackbar: its [text] and the [events] it reports (consumed once shown, later events stay pending). */
data class RecoveryNotice(
    val text: String,
    val events: List<RecoveryEvent>,
)

/** The notice for [events], or null when there is nothing to report. */
fun recoveryNotice(events: List<RecoveryEvent>): RecoveryNotice? = recoveryMessage(events)?.let { RecoveryNotice(it, events) }

/**
 * The one snackbar text for start-up recovery [events] (04-file-format.md#crash-recovery), or null when there
 * is nothing to report. Several recovered documents read as one "Recovered unsaved changes".
 */
fun recoveryMessage(events: List<RecoveryEvent>): String? {
    val parts = ArrayList<String>(MAX_PARTS)
    if (events.any { it is RecoveryEvent.Recovered }) parts += "Recovered unsaved changes."
    events.filterIsInstance<RecoveryEvent.Conflict>().firstOrNull()?.let {
        parts += "Saved a conflict copy: ${it.path.substringAfterLast('/').removeSuffix(".folio")}."
    }
    events.filterIsInstance<RecoveryEvent.Orphaned>().firstOrNull()?.let { parts += "Recovered: ${it.title}." }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private const val MAX_PARTS = 3
