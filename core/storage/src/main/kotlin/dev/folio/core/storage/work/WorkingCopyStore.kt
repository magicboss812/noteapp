package dev.folio.core.storage.work

import dev.folio.core.common.FolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.map
import dev.folio.core.common.outcomeOf
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.container.FolioEntries
import dev.folio.core.model.DocId
import dev.folio.core.storage.repo.ContainerRewriter
import dev.folio.core.storage.repo.ManifestPatch
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Working copies in app files (`work/<docId>/`). Sources live in the library ([libraryFs], paths
 * relative to the library root). Blocking IO: call from the io dispatcher.
 */
class WorkingCopyStore(
    private val appFs: FolioFs,
    private val libraryFs: FolioFs,
) {
    /**
     * Working copy for the `.folio` at [sourcePath]:
     * - a copy made from this file is reused if the file is unchanged (size + mtime) or the copy has
     *   unsaved changes (the packer resolves those as a conflict);
     * - a copy of the same docId made from another file that still exists means [sourcePath] is a manual
     *   duplicate: it gets a new docId first (manifest rewritten, 09-storage-library.md#index);
     * - a dirty copy whose file is gone follows the file to [sourcePath] (moved outside the app);
     * - otherwise the file is unpacked fresh.
     */
    fun open(sourcePath: String): Outcome<WorkingCopy> {
        val stat = libraryFs.stat(sourcePath) ?: return Outcome.Failure("source $sourcePath does not exist")
        return peekDocId(sourcePath).flatMap { docId ->
            val existing = find(docId)
            when {
                existing == null -> {
                    unpack(sourcePath, docId, stat.sizeBytes, stat.modifiedMs)
                }

                existing.base.sourcePath == sourcePath -> {
                    val unchanged = existing.base.sourceSize == stat.sizeBytes && existing.base.sourceMtimeMs == stat.modifiedMs
                    if (unchanged ||
                        existing.isDirty
                    ) {
                        Outcome.Success(existing)
                    } else {
                        unpack(sourcePath, docId, stat.sizeBytes, stat.modifiedMs)
                    }
                }

                libraryFs.exists(existing.base.sourcePath) -> {
                    reassignId(sourcePath).flatMap { open(sourcePath) }
                }

                existing.isDirty -> {
                    existing.updateBase(existing.base.copy(sourcePath = sourcePath)).map { existing }
                }

                else -> {
                    unpack(sourcePath, docId, stat.sizeBytes, stat.modifiedMs)
                }
            }
        }
    }

    private fun reassignId(sourcePath: String): Outcome<Unit> =
        ContainerRewriter(libraryFs).rewrite(sourcePath, sourcePath, ManifestPatch(id = DocId.random().value))

    /** Existing working copy of [docId], or null (missing or unreadable `base.json`). */
    fun find(docId: DocId): WorkingCopy? {
        val bytes = (appFs.readBytes("${WorkingCopy.dirOf(docId)}/${WorkingCopy.BASE_JSON}") as? Outcome.Success)?.value ?: return null
        val base = runCatching { BaseJson.decodeFromString(BaseInfo.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        return if (base.docId == docId.value) WorkingCopy(appFs, docId, base) else null
    }

    /** All working copies with a readable `base.json`. */
    fun all(): List<WorkingCopy> {
        val listing = (appFs.list(WorkingCopy.WORK_DIR) as? Outcome.Success)?.value ?: return emptyList()
        return listing
            .filter { it.isDirectory && !it.path.substringAfterLast('/').startsWith(".") }
            .mapNotNull { find(DocId(it.path.substringAfterLast('/'))) }
    }

    /** Deletes the working copy of [docId]. */
    fun discard(docId: DocId): Outcome<Unit> = appFs.deleteRecursively(WorkingCopy.dirOf(docId))

    /** Reads the document id from the manifest (random access when file-backed, else a stream up to the manifest). */
    private fun peekDocId(sourcePath: String): Outcome<DocId> {
        val local = libraryFs.localFile(sourcePath)
        if (local != null) {
            return FolioContainerReader.open(local).flatMap { reader ->
                reader.use { DocumentCodec.readManifest(it).map { m -> DocId(m.id) } }
            }
        }
        return libraryFs
            .openRead(sourcePath)
            .flatMap { input ->
                outcomeOf("read manifest of $sourcePath") {
                    ZipInputStream(input).use { zip ->
                        generateSequence { zip.nextEntry }.firstOrNull { it.name == FolioEntries.MANIFEST }
                            ?: throw IOException("no manifest in $sourcePath")
                        readLimited(zip, FolioEntries.MAX_MANIFEST_BYTES).decodeToString()
                    }
                }
            }.flatMap { text -> DocumentCodec.parseManifest(text).map { DocId(it.id) } }
    }

    private fun unpack(
        sourcePath: String,
        docId: DocId,
        size: Long,
        mtimeMs: Long,
    ): Outcome<WorkingCopy> {
        val staging = "${WorkingCopy.WORK_DIR}/.unpack-${UUID.randomUUID()}"
        val result =
            libraryFs
                .openRead(sourcePath)
                .flatMap { input -> extract(input, staging) }
                .flatMap {
                    appFs.deleteRecursively(WorkingCopy.dirOf(docId))
                }.flatMap {
                    appFs.move(staging, WorkingCopy.dirOf(docId))
                }.flatMap {
                    val copy = WorkingCopy(appFs, docId, BaseInfo(docId.value, sourcePath, size, mtimeMs, 0L))
                    DocumentCodec.readManifest(copy).flatMap { manifest ->
                        copy.updateBase(copy.base.copy(manifestModifiedMs = manifest.modifiedMs)).map { copy }
                    }
                }
        if (result is Outcome.Failure) appFs.deleteRecursively(staging)
        return result
    }

    private fun extract(
        input: InputStream,
        staging: String,
    ): Outcome<Unit> =
        outcomeOf("unpack into $staging") {
            var total = 0L
            ZipInputStream(input).use { zip ->
                for (entry in generateSequence { zip.nextEntry }) {
                    if (entry.isDirectory || !FolioEntries.isValidName(entry.name)) continue
                    val result =
                        appFs.writeAtomic("$staging/${entry.name}") { out ->
                            total += copyLimited(zip, out, MAX_UNPACKED_BYTES - total)
                        }
                    if (result is Outcome.Failure) throw IOException(result.message, result.cause)
                }
            }
        }

    private companion object {
        // Zip-bomb bound for untrusted library files.
        const val MAX_UNPACKED_BYTES = 8L * 1024 * 1024 * 1024
    }
}

internal fun readLimited(
    input: InputStream,
    maxBytes: Long,
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyLimited(input, out, maxBytes)
    return out.toByteArray()
}

internal fun copyLimited(
    input: InputStream,
    out: java.io.OutputStream,
    maxBytes: Long,
): Long {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var copied = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return copied
        copied += n
        if (copied > maxBytes) throw IOException("entry exceeds $maxBytes bytes")
        out.write(buffer, 0, n)
    }
}

private const val COPY_BUFFER_BYTES = 64 * 1024
