package dev.folio.core.storage.session

/** Whether the document's edits have reached disk (04-file-format.md#write-protocol); the editor shows it as a dot. */
enum class SaveState {
    /** Everything is written to the working copy. */
    Saved,

    /** Edits wait for the idle timer or are being written. */
    Saving,

    /** A write or pack failed; the entries stay pending and a retry writes them again. */
    Error,
}
