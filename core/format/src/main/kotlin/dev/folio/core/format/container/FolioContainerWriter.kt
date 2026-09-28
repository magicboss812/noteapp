package dev.folio.core.format.container

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One entry to write; [open] may be called twice for STORED entries (CRC pass, then copy). */
class WriterEntry(
    val name: String,
    val open: () -> InputStream,
) {
    /** Factory. */
    companion object {
        /** Entry from in-memory bytes. */
        fun of(
            name: String,
            bytes: ByteArray,
        ): WriterEntry = WriterEntry(name) { bytes.inputStream() }
    }
}

/**
 * Writes a `.folio` ZIP: `mimetype` first and STORED, assets and thumbnails STORED (no recompression),
 * everything else DEFLATED level 6, entries in container order (FolioEntries.ordered). The mimetype
 * entry is added if the caller did not pass one. Does not close [out].
 */
object FolioContainerWriter {
    private const val DEFLATE_LEVEL = 6
    private const val BUFFER_BYTES = 64 * 1024

    /** Writes [entries] to [out]. Throws IllegalArgumentException for invalid or duplicate names. */
    fun write(
        out: OutputStream,
        entries: List<WriterEntry>,
    ) {
        val byName = LinkedHashMap<String, WriterEntry>()
        for (e in entries) {
            require(FolioEntries.isValidName(e.name)) { "invalid entry name '${e.name}'" }
            require(byName.put(e.name, e) == null) { "duplicate entry '${e.name}'" }
        }
        byName.getOrPut(FolioEntries.MIMETYPE) { WriterEntry.of(FolioEntries.MIMETYPE, FolioEntries.MIME_TYPE_VALUE.toByteArray()) }
        val zip = ZipOutputStream(out)
        zip.setLevel(DEFLATE_LEVEL)
        val buffer = ByteArray(BUFFER_BYTES)
        for (name in FolioEntries.ordered(byName.keys)) {
            val entry = byName.getValue(name)
            val zipEntry = ZipEntry(name)
            if (FolioEntries.isStored(name)) {
                val (size, crc) = crcAndSize(entry, buffer)
                zipEntry.method = ZipEntry.STORED
                zipEntry.size = size
                zipEntry.compressedSize = size
                zipEntry.crc = crc
            } else {
                zipEntry.method = ZipEntry.DEFLATED
            }
            zip.putNextEntry(zipEntry)
            entry.open().use { input -> copy(input, zip, buffer) }
            zip.closeEntry()
        }
        zip.finish()
    }

    private fun crcAndSize(
        entry: WriterEntry,
        buffer: ByteArray,
    ): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        entry.open().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return size to crc.value
    }

    private fun copy(
        input: InputStream,
        out: OutputStream,
        buffer: ByteArray,
    ) {
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
    }
}
