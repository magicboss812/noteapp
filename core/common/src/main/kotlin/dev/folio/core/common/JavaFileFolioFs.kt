package dev.folio.core.common

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** [FolioFs] over java.io below [root] (library folder with all-files access, app files, temp dirs). */
class JavaFileFolioFs(
    private val root: File,
) : FolioFs {
    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun stat(path: String): FsEntry? {
        val f = resolve(path)
        return if (f.exists()) f.toEntry() else null
    }

    override fun list(dir: String): Outcome<List<FsEntry>> {
        val base = resolve(dir)
        return outcomeOf("list $dir") {
            val children = base.listFiles() ?: throw IOException("not a directory: $dir")
            children.sortedBy { it.name }.map { it.toEntry() }
        }
    }

    override fun readBytes(path: String): Outcome<ByteArray> {
        val file = resolve(path)
        return outcomeOf("read $path") { file.readBytes() }
    }

    override fun localFile(path: String): File = resolve(path)

    override fun openRead(path: String): Outcome<InputStream> {
        val file = resolve(path)
        return outcomeOf("open $path") { file.inputStream().buffered() }
    }

    override fun writeAtomic(
        path: String,
        write: (OutputStream) -> Unit,
    ): Outcome<Unit> {
        val target = resolve(path)
        return outcomeOf("write $path") {
            val dir = target.parentFile
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
            val tmp = File.createTempFile(".${target.name}.", ".tmp", dir)
            try {
                FileOutputStream(tmp).use { out ->
                    val buffered = out.buffered()
                    write(buffered)
                    buffered.flush()
                    out.fd.sync()
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(dir)
            } finally {
                tmp.delete()
            }
        }
    }

    override fun move(
        from: String,
        to: String,
    ): Outcome<Unit> {
        val source = resolve(from)
        val target = resolve(to)
        return outcomeOf("move $from -> $to") {
            target.parentFile.mkdirs()
            Files.move(source.toPath(), target.toPath())
            Unit
        }
    }

    override fun delete(path: String): Outcome<Unit> {
        val file = resolve(path)
        return outcomeOf("delete $path") {
            Files.deleteIfExists(file.toPath())
            Unit
        }
    }

    override fun deleteRecursively(path: String): Outcome<Unit> {
        val file = resolve(path)
        require(path.isNotEmpty()) { "refusing to delete the root" }
        return outcomeOf("delete $path") {
            if (file.exists() && !file.deleteRecursively()) throw IOException("cannot delete $path")
        }
    }

    override fun mkdirs(dir: String): Outcome<Unit> {
        val d = resolve(dir)
        return outcomeOf("mkdirs $dir") {
            if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create $dir")
        }
    }

    private fun resolve(path: String): File {
        if (path.isEmpty()) return root
        require(!path.startsWith("/") && '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "path must be relative, '/'-separated, without empty, '.' or '..' segments: $path"
        }
        return File(root, path)
    }

    private fun File.toEntry(): FsEntry =
        FsEntry(
            path = relativeTo(root).invariantSeparatorsPath,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else length(),
            modifiedMs = lastModified(),
        )

    // Makes the rename durable. Some file systems refuse to open directories; the rename is then as
    // durable as the platform allows (ADR-004: supported on the Pad 7).
    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            FolioLog.d(TAG, "directory fsync unsupported: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "FolioFs"
    }
}
