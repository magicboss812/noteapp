package dev.folio.core.storage.index

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Library index v1 (09-storage-library.md#index). Disposable: a schema change bumps the version with a
 * destructive migration and a full rescan (file-format-storage rule).
 */
@Database(
    entities = [DocumentRow::class, FolderRow::class, TagRow::class, LinkRow::class, DocFtsRow::class],
    version = 1,
    exportSchema = true,
)
abstract class IndexDb : RoomDatabase() {
    /** All index queries. */
    abstract fun dao(): IndexDao

    /** File name in app-private databases. */
    companion object {
        /** Database file name. */
        const val NAME = "index.db"
    }
}

/** Builds FTS4 MATCH expressions: every token must match as a prefix (09-storage-library.md#search). */
object FtsQuery {
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** `"kin" "phys"` -> `kin* phys*`; null if [text] has no searchable token. */
    fun of(text: String): String? =
        TOKEN
            .findAll(text.lowercase())
            .map { "${it.value}*" }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
}
