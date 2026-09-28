package dev.folio.core.storage.index

import dev.folio.core.common.FsEntry
import dev.folio.core.format.container.FolioEntries
import dev.folio.core.format.manifest.Manifest

/** Row of a readable document: everything comes from its manifest. */
internal fun okRow(
    file: FsEntry,
    m: Manifest,
    hasCover: Boolean,
) = DocumentRow(
    path = file.path,
    docId = m.id,
    folderPath = LibraryScanner.parentOf(file.path),
    title = m.title.ifBlank { stem(file.path) },
    createdMs = m.createdMs,
    modifiedMs = m.modifiedMs,
    favorite = m.favorite,
    pageCount = m.pages.size,
    textPreview = m.textPreview,
    fileSize = file.sizeBytes,
    fileMtime = file.modifiedMs,
    coverThumbPath = if (hasCover) FolioEntries.COVER else null,
    formatVersion = m.formatVersion,
    status = DocStatus.OK,
)

/** Row of a file that cannot be opened (newer format or corrupt): listed with its file name and a badge. */
internal fun badRow(
    file: FsEntry,
    status: DocStatus,
) = DocumentRow(
    path = file.path,
    docId = "",
    folderPath = LibraryScanner.parentOf(file.path),
    title = stem(file.path),
    createdMs = file.modifiedMs,
    modifiedMs = file.modifiedMs,
    favorite = false,
    pageCount = 0,
    textPreview = "",
    fileSize = file.sizeBytes,
    fileMtime = file.modifiedMs,
    coverThumbPath = null,
    formatVersion = 0,
    status = status,
)

private fun stem(path: String): String = path.substringAfterLast('/').removeSuffix(LibraryScanner.FOLIO_EXT)
