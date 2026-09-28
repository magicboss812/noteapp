package dev.folio.core.format.container

import dev.folio.core.common.Outcome
import dev.folio.core.format.FormatError
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Read access to the entries of a `.folio` document (a ZIP or an unpacked working copy). */
interface EntryReader {
    /** All entry names. */
    fun names(): Set<String>

    /** Whole entry content; fails with [FormatError.MissingEntry] or [FormatError.Corrupt] (too large, unreadable). */
    fun read(
        name: String,
        maxBytes: Long,
    ): Outcome<ByteArray>
}

/** Random-access reader of a `.folio` ZIP file (java.util.zip.ZipFile). Blocking: use the io dispatcher. */
class FolioContainerReader private constructor(
    private val zip: ZipFile,
) : EntryReader,
    Closeable {
    private val names: Set<String> =
        zip
            .entries()
            .asSequence()
            .filter { !it.isDirectory && FolioEntries.isValidName(it.name) }
            .map { it.name }
            .toSet()

    override fun names(): Set<String> = names

    override fun read(
        name: String,
        maxBytes: Long,
    ): Outcome<ByteArray> {
        val entry = zip.getEntry(name)?.takeIf { name in names }
        return when {
            entry == null -> {
                missing(name)
            }

            entry.size > maxBytes -> {
                corrupt(name, "entry is ${entry.size} bytes, limit $maxBytes")
            }

            else -> {
                try {
                    zip.getInputStream(entry).use { Outcome.Success(readBounded(it, maxBytes, name)) }
                } catch (e: IOException) {
                    corrupt(name, "unreadable: ${e.message}", e)
                }
            }
        }
    }

    /** Opens a stream for large entries (assets); caller closes it. */
    fun open(name: String): Outcome<InputStream> {
        val entry = zip.getEntry(name)?.takeIf { name in names } ?: return missing(name)
        return try {
            Outcome.Success(zip.getInputStream(entry))
        } catch (e: IOException) {
            corrupt(name, "unreadable: ${e.message}", e)
        }
    }

    override fun close() = zip.close()

    /** Factory. */
    companion object {
        /** Opens [file]; a file that is not a ZIP fails with [FormatError.Corrupt]. */
        fun open(file: File): Outcome<FolioContainerReader> =
            try {
                Outcome.Success(FolioContainerReader(ZipFile(file)))
            } catch (e: ZipException) {
                corrupt(file.name, "not a zip archive", e)
            } catch (e: IOException) {
                Outcome.Failure("cannot open ${file.name}: ${e.message}", e)
            }
    }
}

private const val MAX_ARRAY_BYTES = Int.MAX_VALUE - 8

internal fun readBounded(
    input: InputStream,
    maxBytes: Long,
    name: String,
): ByteArray {
    // One byte past the limit detects oversize entries; the array cap keeps huge limits from overflowing.
    val limit = if (maxBytes >= MAX_ARRAY_BYTES) MAX_ARRAY_BYTES else maxBytes.toInt() + 1
    val bytes = input.readNBytes(limit)
    if (bytes.size > maxBytes) throw IOException("$name exceeds $maxBytes bytes")
    return bytes
}

internal fun missing(name: String): Outcome.Failure = Outcome.Failure("missing entry $name", FormatError.MissingEntry(name))

internal fun corrupt(
    name: String,
    detail: String,
    cause: Throwable? = null,
): Outcome.Failure = Outcome.Failure("corrupt entry $name: $detail", FormatError.Corrupt(name, detail, cause))
