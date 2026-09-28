package dev.folio.core.common

import java.io.InputStream
import java.io.OutputStream

/** One entry of a directory listing. Paths are relative to the FolioFs root, '/'-separated. */
data class FsEntry(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

/**
 * File access below one root (the library folder or app files in the app, a temp dir in tests).
 * Paths are relative to the root and '/'-separated ("" is the root). Absolute paths, '\\' and ".."
 * segments are programmer errors and throw IllegalArgumentException in every method.
 * Calls block: run them on FolioDispatchers.io.
 */
interface FolioFs {
    /** True if a file or directory exists at [path]. */
    fun exists(path: String): Boolean

    /** Size and modification time of [path], or null if it does not exist. */
    fun stat(path: String): FsEntry?

    /** Direct children of [dir] ("" is the root). */
    fun list(dir: String): Outcome<List<FsEntry>>

    /** Whole file content. */
    fun readBytes(path: String): Outcome<ByteArray>

    /** Local file behind [path] for random-access readers (ZipFile), or null if not backed by java.io files. */
    fun localFile(path: String): java.io.File? = null

    /** Stream over the file; the caller closes it. */
    fun openRead(path: String): Outcome<InputStream>

    /**
     * Atomic write (file-format-storage rule): unique temp file in the same directory, [write] fills
     * it, flush + fsync, rename over [path], fsync the directory where supported. Readers never see a
     * partial file; the temp file is removed on failure (an exception thrown by [write] included).
     * Creates parent dirs.
     */
    fun writeAtomic(
        path: String,
        write: (OutputStream) -> Unit,
    ): Outcome<Unit>

    /** [writeAtomic] with in-memory content. */
    fun writeBytesAtomic(
        path: String,
        bytes: ByteArray,
    ): Outcome<Unit> = writeAtomic(path) { it.write(bytes) }

    /** Moves or renames; fails if [to] exists. Creates parent dirs of [to]. */
    fun move(
        from: String,
        to: String,
    ): Outcome<Unit>

    /** Deletes a file or an empty directory. Deleting a missing path succeeds. */
    fun delete(path: String): Outcome<Unit>

    /** Deletes a file or a directory with everything below it. Deleting a missing path succeeds. */
    fun deleteRecursively(path: String): Outcome<Unit>

    /** Creates [dir] and its parents. */
    fun mkdirs(dir: String): Outcome<Unit>
}
