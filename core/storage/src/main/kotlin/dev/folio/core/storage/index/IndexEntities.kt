package dev.folio.core.storage.index

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index

/** Index status of a document file. */
enum class DocStatus { OK, TOO_NEW, CORRUPT }

/**
 * One `.folio` file (09-storage-library.md#index). Keyed by library-relative [path] so manual copies
 * with the same [docId] are both listed (A-013).
 */
@Entity(tableName = "documents", primaryKeys = ["path"], indices = [Index("docId"), Index("folderPath")])
data class DocumentRow(
    val path: String,
    val docId: String,
    val folderPath: String,
    val title: String,
    val createdMs: Long,
    val modifiedMs: Long,
    val favorite: Boolean,
    val pageCount: Int,
    val textPreview: String,
    val fileSize: Long,
    val fileMtime: Long,
    val coverThumbPath: String?,
    val formatVersion: Int,
    val status: DocStatus,
)

/** A visible library folder ("" is the root, not stored). */
@Entity(tableName = "folders", primaryKeys = ["path"], indices = [Index("parentPath")])
data class FolderRow(
    val path: String,
    val parentPath: String,
    val name: String,
    val tint: String?,
    val createdMs: Long,
)

/** Tag of a document file. */
@Entity(
    tableName = "tags",
    primaryKeys = ["path", "tag"],
    indices = [Index("tag")],
    foreignKeys = [ForeignKey(DocumentRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE)],
)
data class TagRow(
    val path: String,
    val tag: String,
)

/** Note link from a document file to a document id. */
@Entity(
    tableName = "links",
    primaryKeys = ["fromPath", "toDocId"],
    indices = [Index("toDocId")],
    foreignKeys = [ForeignKey(DocumentRow::class, ["path"], ["fromPath"], onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE)],
)
data class LinkRow(
    val fromPath: String,
    val toDocId: String,
)

/** Full-text search over title and body (`search/text.txt`). */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["path"])
@Entity(tableName = "doc_fts")
data class DocFtsRow(
    val path: String,
    val title: String,
    val body: String,
)
