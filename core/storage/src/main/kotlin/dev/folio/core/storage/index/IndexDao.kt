package dev.folio.core.storage.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Index queries (writes happen through [LibraryScanner] and the repositories). */
@Dao
interface IndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocuments(rows: List<DocumentRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(rows: List<FolderRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(rows: List<TagRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(rows: List<LinkRow>)

    @Insert
    suspend fun insertFts(rows: List<DocFtsRow>)

    @Query("DELETE FROM tags WHERE path IN (:paths)")
    suspend fun deleteTags(paths: List<String>)

    @Query("DELETE FROM links WHERE fromPath IN (:paths)")
    suspend fun deleteLinks(paths: List<String>)

    @Query("DELETE FROM doc_fts WHERE path IN (:paths)")
    suspend fun deleteFts(paths: List<String>)

    @Query("DELETE FROM documents WHERE path IN (:paths)")
    suspend fun deleteDocuments(paths: List<String>)

    @Query("DELETE FROM folders WHERE path IN (:paths)")
    suspend fun deleteFolders(paths: List<String>)

    @Query("SELECT path, fileSize, fileMtime FROM documents")
    suspend fun fileStamps(): List<FileStamp>

    @Query("SELECT path FROM folders")
    suspend fun folderPaths(): List<String>

    @Query("SELECT * FROM documents WHERE path = :path")
    suspend fun document(path: String): DocumentRow?

    @Query("SELECT * FROM documents WHERE docId = :docId ORDER BY status, path")
    suspend fun documentsById(docId: String): List<DocumentRow>

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun documentCount(): Int

    @Query("SELECT * FROM documents WHERE folderPath = :folderPath ORDER BY modifiedMs DESC")
    fun documentsIn(folderPath: String): Flow<List<DocumentRow>>

    @Query("SELECT * FROM documents WHERE favorite = 1 ORDER BY modifiedMs DESC")
    fun favorites(): Flow<List<DocumentRow>>

    @Query("SELECT d.* FROM documents d JOIN tags t ON t.path = d.path WHERE t.tag = :tag ORDER BY d.modifiedMs DESC")
    fun documentsWithTag(tag: String): Flow<List<DocumentRow>>

    @Query("SELECT DISTINCT tag FROM tags ORDER BY tag")
    fun allTags(): Flow<List<String>>

    @Query("SELECT * FROM tags ORDER BY path, tag")
    fun tagRows(): Flow<List<TagRow>>

    @Query("SELECT * FROM folders ORDER BY path")
    fun folders(): Flow<List<FolderRow>>

    /** FTS match, newest first; [match] comes from [FtsQuery.of]. The repository ranks title hits first. */
    @Query("SELECT d.* FROM documents d JOIN doc_fts f ON f.path = d.path WHERE doc_fts MATCH :match ORDER BY d.modifiedMs DESC")
    suspend fun search(match: String): List<DocumentRow>

    /**
     * FTS match with a snippet (markers char(1)/char(2)), newest first, optionally limited to [folder] (and its
     * subfolders) and to documents with [tag]; null means no limit. The repository ranks title hits first.
     */
    @Query(
        """
        SELECT d.*, snippet(doc_fts, char(1), char(2), '…', -1, 12) AS snippet
        FROM doc_fts JOIN documents d ON d.path = doc_fts.path
        WHERE doc_fts MATCH :match
          AND (:folder IS NULL OR d.folderPath = :folder OR substr(d.folderPath, 1, length(:folder) + 1) = :folder || '/')
          AND (:tag IS NULL OR EXISTS (SELECT 1 FROM tags t WHERE t.path = d.path AND t.tag = :tag))
        ORDER BY d.modifiedMs DESC
        """,
    )
    suspend fun searchHits(
        match: String,
        folder: String?,
        tag: String?,
    ): List<SearchHitRow>

    @Query("SELECT l.fromPath FROM links l WHERE l.toDocId = :docId")
    suspend fun backlinkPaths(docId: String): List<String>
}

/** Size and mtime of an indexed file (incremental scan). */
data class FileStamp(
    val path: String,
    val fileSize: Long,
    val fileMtime: Long,
)
