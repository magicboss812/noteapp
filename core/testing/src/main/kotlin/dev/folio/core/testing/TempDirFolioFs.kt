package dev.folio.core.testing

import dev.folio.core.common.FolioFs
import dev.folio.core.common.FsEntry
import dev.folio.core.common.Outcome
import dev.folio.core.common.outcomeOf
import org.junit.rules.ExternalResource
import java.io.File
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

    override fun list(dir: String): Outcome<List<FsEntry>> =
        outcomeOf("list $dir") {
            val base = resolve(dir)
            val children = base.listFiles() ?: throw IOException("not a directory: $dir")
            children.sortedBy { it.name }.map { it.toEntry() }
        }

    override fun readBytes(path: String): Outcome<ByteArray> = outcomeOf("read $path") { resolve(path).readBytes() }

    override fun writeBytesAtomic(
        path: String,
        bytes: ByteArray,
    ): Outcome<Unit> =
        outcomeOf("write $path") {
            val target = resolve(path)
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Unit
        }

    override fun move(
        from: String,
        to: String,
    ): Outcome<Unit> =
        outcomeOf("move $from -> $to") {
            val target = resolve(to)
            target.parentFile.mkdirs()
            Files.move(resolve(from).toPath(), target.toPath())
            Unit
        }

    override fun delete(path: String): Outcome<Unit> =
        outcomeOf("delete $path") {
            Files.deleteIfExists(resolve(path).toPath())
            Unit
        }

    override fun mkdirs(dir: String): Outcome<Unit> =
        outcomeOf("mkdirs $dir") {
            val d = resolve(dir)
            if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create $dir")
        }

    private fun resolve(path: String): File {
        require(path.split('/').none { it == ".." }) { "'..' is not allowed: $path" }
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
