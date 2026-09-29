package dev.folio.core.storage.work

import dev.folio.core.common.Clock
import dev.folio.core.common.FolioFs
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.getOrNull
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.container.FolioContainerWriter
import dev.folio.core.format.container.WriterEntry
import dev.folio.core.storage.repo.FileNames
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Outcome of [Packer.pack]. */
sealed interface PackResult {
    /** Nothing to do: the working copy has no unsaved entries. */
    data object Clean : PackResult

    /** Written to the source file. */
    data class Packed(
        val path: String,
    ) : PackResult

    /** The source changed externally while the copy was dirty: written beside it instead (04-file-format.md#conflicts). */
    data class ConflictCopy(
        val path: String,
        val originalPath: String,
    ) : PackResult

    /** The source file is gone (deleted externally); the working copy is kept for recovery. */
    data object SourceMissing : PackResult
}

/**
 * Packs a working copy into its `.folio` (04-file-format.md#write-protocol): temp file beside the
 * target, fsync, atomic rename. The file as it was when the copy was opened is saved once per working
 * copy to app-private `backup/<docId>.folio` before it is first replaced (A-012). Blocking: io
 * dispatcher, serialized with entry writes by the session.
 */
class Packer(
    private val libraryFs: FolioFs,
    private val appFs: FolioFs,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Packs [copy] if dirty (or [force]). */
    fun pack(
        copy: WorkingCopy,
        force: Boolean = false,
    ): Outcome<PackResult> {
        if (!copy.isDirty && !force) return Outcome.Success(PackResult.Clean)
        val base = copy.base
        val stat = libraryFs.stat(base.sourcePath) ?: return Outcome.Success(PackResult.SourceMissing)
        val changedExternally = stat.sizeBytes != base.sourceSize || stat.modifiedMs != base.sourceMtimeMs
        val target = if (changedExternally) conflictPath(copy) else base.sourcePath
        if (!changedExternally && !base.backupDone) backup(copy)
        return write(copy, target).flatMap {
            val written = libraryFs.stat(target)
            copy
                .updateBase(
                    base.copy(
                        sourcePath = target,
                        sourceSize = written?.sizeBytes ?: 0L,
                        sourceMtimeMs = written?.modifiedMs ?: 0L,
                        dirtyEntries = emptySet(),
                        lastPackMs = clock.nowMs(),
                        backupDone = base.backupDone || !changedExternally,
                    ),
                ).flatMap {
                    Outcome.Success(if (changedExternally) PackResult.ConflictCopy(target, base.sourcePath) else PackResult.Packed(target))
                }
        }
    }

    private fun write(
        copy: WorkingCopy,
        target: String,
    ): Outcome<Unit> {
        val entries =
            copy.names().map { name ->
                WriterEntry(name) {
                    when (val r = copy.open(name)) {
                        is Outcome.Success -> r.value
                        is Outcome.Failure -> throw IOException(r.message, r.cause)
                    }
                }
            }
        return libraryFs.writeAtomic(target) { out -> FolioContainerWriter.write(out, entries) }
    }

    // Best effort: a failed backup must not block saving the user's work.
    private fun backup(copy: WorkingCopy) {
        val source = copy.base.sourcePath
        val result =
            libraryFs.openRead(source).flatMap { input ->
                input.use { appFs.writeAtomic("$BACKUP_DIR/${copy.docId.value}.folio") { out -> it.copyTo(out) } }
            }
        if (result is Outcome.Failure) FolioLog.w(TAG, "backup of $source failed: ${result.message}", result.cause)
    }

    private fun conflictPath(copy: WorkingCopy): String {
        val source = copy.base.sourcePath
        val dir = source.substringBeforeLast('/', "")
        val title =
            FileNames.stemOf(
                DocumentCodec
                    .readManifest(copy)
                    .getOrNull()
                    ?.title
                    ?.ifBlank { null }
                    ?: source.substringAfterLast('/').removeSuffix(FileNames.FOLIO_EXT),
            )
        val stamp = CONFLICT_STAMP.format(Instant.ofEpochMilli(clock.nowMs()).atZone(zone))
        val stem = "$title (conflict $stamp)"
        val candidates = sequenceOf("$stem.folio") + generateSequence(2) { it + 1 }.map { "$stem ($it).folio" }
        return candidates.map { if (dir.isEmpty()) it else "$dir/$it" }.first { !libraryFs.exists(it) }
    }

    /** Layout constants. */
    companion object {
        /** App-private backup directory. */
        const val BACKUP_DIR = "backup"
        private const val TAG = "Packer"
        private val CONFLICT_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")
    }
}
