package dev.folio.core.format

/**
 * Typed reasons why a `.folio` file or entry cannot be read. Carried as `Outcome.Failure.cause` so
 * callers can branch on them (`failure.cause as? FormatError`).
 */
sealed class FormatError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The file was written by a newer major format version than this app reads. */
    class FormatTooNew(
        val found: Int,
        val supported: Int,
    ) : FormatError("format version $found is newer than supported $supported")

    /** An entry exists but its content is invalid. */
    class Corrupt(
        val entry: String,
        detail: String,
        cause: Throwable? = null,
    ) : FormatError("corrupt entry $entry: $detail", cause)

    /** A required entry is missing. */
    class MissingEntry(
        val entry: String,
    ) : FormatError("missing entry $entry")
}
