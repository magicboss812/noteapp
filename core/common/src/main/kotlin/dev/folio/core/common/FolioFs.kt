package dev.folio.core.common

/** One entry of a directory listing. Paths are relative to the FolioFs root, '/'-separated. */
data class FsEntry(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

/**
 * File access below one root (the library folder in the app, a temp dir in tests).
 * Paths are relative to the root and '/'-separated; ".." segments are rejected.
 * Calls block: run them on FolioDispatchers.io.
 */
interface FolioFs {
    /** True if a file or directory exists at [path]. */
    fun exists(path: String): Boolean

    /** Direct children of [dir] ("" is the root). */
    fun list(dir: String): Outcome<List<FsEntry>>

    /** Whole file content. */
    fun readBytes(path: String): Outcome<ByteArray>

    /** Writes via a temp file + rename so readers never see a partial file. Creates parent dirs. */
    fun writeBytesAtomic(
        path: String,
        bytes: ByteArray,
    ): Outcome<Unit>

    /** Moves or renames; fails if [to] exists. Creates parent dirs of [to]. */
    fun move(
        from: String,
        to: String,
    ): Outcome<Unit>

    /** Deletes a file or an empty directory. Deleting a missing path succeeds. */
    fun delete(path: String): Outcome<Unit>

    /** Creates [dir] and its parents. */
    fun mkdirs(dir: String): Outcome<Unit>
}
