package dev.folio.core.testing

import dev.folio.core.common.FolioFs
import dev.folio.core.common.FsEntry
import dev.folio.core.common.Outcome
import dev.folio.core.common.outcomeOf
import org.junit.rules.ExternalResource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JUnit rule: a real [FolioFs] over a fresh temp directory, deleted after the test. */
class TempDirFolioFs :
    ExternalResource(),
    FolioFs {
    private var rootDir: File? = null

    /** The temp directory backing this file system (valid during the test). */
    val root: File
        get() = checkNotNull(rootDir) { "TempDirFolioFs is used outside a running test" }

    override fun before() {
        rootDir = Files.createTempDirectory("folio-fs").toFile()
    }

    override fun after() {
        rootDir?.deleteRecursively()
        rootDir = null
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

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

    override fun writeBytesAtomic(
        path: String,
        bytes: ByteArray,
    ): Outcome<Unit> {
        val target = resolve(path)
        return outcomeOf("write $path") {
            val dir = target.parentFile
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
            val tmp = File.createTempFile(".${target.name}.", ".tmp", dir)
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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

    override fun mkdirs(dir: String): Outcome<Unit> {
        val d = resolve(dir)
        return outcomeOf("mkdirs $dir") {
            if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create $dir")
        }
    }

    private fun resolve(path: String): File {
        require(!path.startsWith("/") && '\\' !in path && path.split('/').none { it == ".." }) {
            "path must be relative, '/'-separated, without '..': $path"
        }
        return if (path.isEmpty()) root else File(root, path)
    }

    private fun File.toEntry(): FsEntry =
        FsEntry(
            path = relativeTo(root).invariantSeparatorsPath,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else length(),
            modifiedMs = lastModified(),
        )
}
