package dev.folio.core.storage.repo

import dev.folio.core.common.Clock
import dev.folio.core.common.FolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.getOrNull
import dev.folio.core.common.map
import dev.folio.core.storage.library.LibraryLayout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One item in the bin (09-storage-library.md#trash). */
data class BinEntry(
    /** Library path inside `.trash/`. */
    val trashPath: String,
    val originalPath: String,
    val deletedMs: Long,
    val docId: String?,
    val isFolder: Boolean,
) {
    /** Display name (original file or folder name). */
    val name: String get() = FileNames.nameOf(originalPath)
}

@Serializable
private data class TrashSidecar(
    val originalPath: String,
    val deletedMs: Long,
    val docId: String? = null,
)

private val SidecarJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/**
 * `.trash/<epochMs>__<name>` plus sidecar `<same>.trash.json`. Permanent deletion only from here
 * (Delete forever, Empty bin, retention purge). Blocking IO: io dispatcher.
 */
internal class Bin(
    private val fs: FolioFs,
    private val clock: Clock,
) {
    fun moveToBin(
        path: String,
        docId: String?,
    ): Outcome<BinEntry> {
        val now = clock.nowMs()
        val stat = fs.stat(path) ?: return Outcome.Failure("$path does not exist")
        val base = "${LibraryLayout.TRASH}/${now}__${FileNames.nameOf(path)}"
        val trashPath = generateSequence(0) { it + 1 }.map { if (it == 0) base else "$base~$it" }.first { !fs.exists(it) }
        val sidecar = TrashSidecar(path, now, docId)
        return fs
            .writeBytesAtomic(sidecarOf(trashPath), SidecarJson.encodeToString(TrashSidecar.serializer(), sidecar).encodeToByteArray())
            .flatMap { fs.move(path, trashPath) }
            .map { BinEntry(trashPath, path, now, docId, stat.isDirectory) }
    }

    fun entries(): List<BinEntry> {
        val children = fs.list(LibraryLayout.TRASH).getOrNull() ?: return emptyList()
        val names = children.associateBy { it.path }
        return children
            .filter { it.path.endsWith(SIDECAR_EXT) }
            .mapNotNull { side ->
                val trashPath = side.path.removeSuffix(SIDECAR_EXT)
                val item = names[trashPath] ?: return@mapNotNull null
                val meta =
                    fs.readBytes(side.path).getOrNull()?.let {
                        runCatching { SidecarJson.decodeFromString(TrashSidecar.serializer(), it.decodeToString()) }.getOrNull()
                    } ?: return@mapNotNull null
                BinEntry(trashPath, meta.originalPath, meta.deletedMs, meta.docId, item.isDirectory)
            }.sortedByDescending { it.deletedMs }
    }

    /** Moves [entry] back to its original path (parents recreated; name collision -> " (2)"). Returns the new path. */
    fun restore(entry: BinEntry): Outcome<String> {
        val parent = FileNames.parentOf(entry.originalPath)
        val name = FileNames.nameOf(entry.originalPath)
        val ext = if (!entry.isFolder && name.endsWith(FileNames.FOLIO_EXT)) FileNames.FOLIO_EXT else ""
        val target = FileNames.unique(fs, parent, name.removeSuffix(ext), ext)
        return fs
            .mkdirs(parent)
            .flatMap { fs.move(entry.trashPath, target) }
            .flatMap { fs.delete(sidecarOf(entry.trashPath)) }
            .map { target }
    }

    fun deleteForever(entry: BinEntry): Outcome<Unit> =
        fs.deleteRecursively(entry.trashPath).flatMap { fs.delete(sidecarOf(entry.trashPath)) }

    /** Deletes every bin entry. Returns the number deleted. */
    fun empty(): Outcome<Int> = purge { true }

    /** Deletes entries older than [retentionDays]. Returns the number deleted. */
    fun purgeOlderThan(retentionDays: Int): Outcome<Int> {
        val cutoff = clock.nowMs() - retentionDays * DAY_MS
        return purge { it.deletedMs < cutoff }
    }

    private fun purge(which: (BinEntry) -> Boolean): Outcome<Int> {
        var count = 0
        for (entry in entries().filter(which)) {
            val r = deleteForever(entry)
            if (r is Outcome.Failure) return r
            count++
        }
        return Outcome.Success(count)
    }

    private fun sidecarOf(trashPath: String) = "$trashPath$SIDECAR_EXT"

    companion object {
        const val SIDECAR_EXT = ".trash.json"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
