package dev.folio.core.storage.work

import dev.folio.core.common.FolioFs
import dev.folio.core.common.FsEntry
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.format.FormatError
import dev.folio.core.format.container.EntryReader
import dev.folio.core.model.DocId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream

/**
 * `base.json` of a working copy (04-file-format.md#write-protocol). [sourcePath] is relative to the
 * library root; size + mtime, then [sourceCrc32] when only the mtime differs, detect external changes
 * (04-file-format.md#conflicts, A-022).
 */
@Serializable
data class BaseInfo(
    val docId: String,
    val sourcePath: String,
    val sourceSize: Long,
    val sourceMtimeMs: Long,
    val manifestModifiedMs: Long,
    val dirtyEntries: Set<String> = emptySet(),
    val lastPackMs: Long = 0L,
    val backupDone: Boolean = false,
    /** When recovery first reported the source as missing; the copy is kept 7 days from then. */
    val orphanSinceMs: Long? = null,
    /** CRC-32 of the file bytes this copy last packed (null before the first pack). */
    val sourceCrc32: Long? = null,
) {
    /**
     * True if the library file with [stat] is still the version this base recorded: same size and
     * mtime, or same size and same bytes as the last pack. Shared storage on the Pad 7 reports a
     * replaced file's old mtime some time after the rename, so the mtime alone is not trusted.
     */
    internal fun matchesSource(
        libraryFs: FolioFs,
        stat: FsEntry,
    ): Boolean {
        if (stat.sizeBytes != sourceSize) return false
        if (stat.modifiedMs == sourceMtimeMs) return true
        val packed = sourceCrc32 ?: return false
        return crc32Of(libraryFs, sourcePath) == packed
    }
}

/** CRC-32 of the file at [path] in [fs], streamed; null if it cannot be read. Blocking IO. */
internal fun crc32Of(
    fs: FolioFs,
    path: String,
): Long? {
    val input = (fs.openRead(path) as? Outcome.Success)?.value ?: return null
    return input.use {
        CheckedInputStream(it, CRC32()).use { checked ->
            val buffer = ByteArray(CRC_BUFFER_BYTES)
            while (checked.read(buffer) >= 0) Unit
            checked.checksum.value
        }
    }
}

private const val CRC_BUFFER_BYTES = 64 * 1024

/**
 * Unpacked document in app-private `work/<docId>/` (same layout as the ZIP) plus `base.json`.
 * Blocking IO: call from the io dispatcher, one writer at a time (the session serializes access).
 */
class WorkingCopy internal constructor(
    private val appFs: FolioFs,
    val docId: DocId,
    base: BaseInfo,
) : EntryReader {
    /** Current `base.json` state. */
    var base: BaseInfo = base
        private set

    /** Directory below the app files root. */
    val dir: String = dirOf(docId)

    /** True while entries changed since the last pack. */
    val isDirty: Boolean get() = base.dirtyEntries.isNotEmpty()

    override fun names(): Set<String> {
        val out = HashSet<String>()
        collect("", out)
        return out
    }

    override fun read(
        name: String,
        maxBytes: Long,
    ): Outcome<ByteArray> {
        val stat = appFs.stat(path(name))
        return when {
            stat == null || stat.isDirectory -> Outcome.Failure("missing entry $name", FormatError.MissingEntry(name))
            stat.sizeBytes > maxBytes -> Outcome.Failure("entry $name too large", FormatError.Corrupt(name, "${stat.sizeBytes} bytes"))
            else -> appFs.readBytes(path(name))
        }
    }

    /** Stream over entry [name]; the caller closes it. */
    fun open(name: String): Outcome<InputStream> = appFs.openRead(path(name))

    /**
     * Records the entries as dirty in `base.json` first, then writes them atomically one by one
     * (null = delete). A crash in between leaves at worst a dirty mark on an unchanged entry (an extra
     * pack), never a written change that recovery does not see.
     */
    fun writeEntries(changes: Map<String, ByteArray?>): Outcome<Unit> {
        changes.keys.forEach { require(it != BASE_JSON && !it.startsWith(".")) { "reserved entry name $it" } }
        return updateBase(base.copy(dirtyEntries = base.dirtyEntries + changes.keys)).flatMap {
            changes.entries.fold<Map.Entry<String, ByteArray?>, Outcome<Unit>>(Outcome.Success(Unit)) { acc, (name, bytes) ->
                acc.flatMap { if (bytes == null) appFs.delete(path(name)) else appFs.writeBytesAtomic(path(name), bytes) }
            }
        }
    }

    /** Replaces and persists `base.json`. */
    fun updateBase(newBase: BaseInfo): Outcome<Unit> =
        appFs.writeBytesAtomic("$dir/$BASE_JSON", BaseJson.encodeToString(BaseInfo.serializer(), newBase).encodeToByteArray()).flatMap {
            base = newBase
            Outcome.Success(Unit)
        }

    private fun path(name: String): String = "$dir/$name"

    private fun collect(
        relative: String,
        out: MutableSet<String>,
    ) {
        val listing = appFs.list(if (relative.isEmpty()) dir else "$dir/$relative")
        val children = (listing as? Outcome.Success)?.value ?: return
        for (child in children) {
            val name = child.path.substringAfterLast('/')
            if (name.startsWith(".")) continue
            val rel = if (relative.isEmpty()) name else "$relative/$name"
            when {
                child.isDirectory -> collect(rel, out)
                rel != BASE_JSON -> out += rel
            }
        }
    }

    /** Layout constants. */
    companion object {
        /** Name of the state file inside the working copy directory. */
        const val BASE_JSON = "base.json"

        /** Parent of all working copies (app files). */
        const val WORK_DIR = "work"

        /** Working copy directory of [docId]. */
        fun dirOf(docId: DocId): String = "$WORK_DIR/${docId.value}"
    }
}

internal val BaseJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
