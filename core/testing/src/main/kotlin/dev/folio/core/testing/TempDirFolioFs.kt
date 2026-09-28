package dev.folio.core.testing

import dev.folio.core.common.FolioFs
import dev.folio.core.common.FsEntry
import dev.folio.core.common.JavaFileFolioFs
import dev.folio.core.common.Outcome
import org.junit.rules.ExternalResource
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** JUnit rule: a real [FolioFs] ([JavaFileFolioFs]) over a fresh temp directory, deleted after the test. */
class TempDirFolioFs :
    ExternalResource(),
    FolioFs {
    private var rootDir: File? = null
    private var fs: FolioFs? = null

    /** The temp directory backing this file system (valid during the test). */
    val root: File
        get() = checkNotNull(rootDir) { "TempDirFolioFs is used outside a running test" }

    private val delegate: FolioFs
        get() = checkNotNull(fs) { "TempDirFolioFs is used outside a running test" }

    override fun before() {
        rootDir = Files.createTempDirectory("folio-fs").toFile().also { fs = JavaFileFolioFs(it) }
    }

    override fun after() {
        rootDir?.deleteRecursively()
        rootDir = null
        fs = null
    }

    /** A separate [FolioFs] rooted at [relativeDir] below [root] (e.g. app files next to a library). */
    fun sub(relativeDir: String): FolioFs = JavaFileFolioFs(File(root, relativeDir).apply { mkdirs() })

    override fun exists(path: String): Boolean = delegate.exists(path)

    override fun stat(path: String): FsEntry? = delegate.stat(path)

    override fun list(dir: String): Outcome<List<FsEntry>> = delegate.list(dir)

    override fun readBytes(path: String): Outcome<ByteArray> = delegate.readBytes(path)

    override fun openRead(path: String): Outcome<InputStream> = delegate.openRead(path)

    override fun writeAtomic(
        path: String,
        write: (OutputStream) -> Unit,
    ): Outcome<Unit> = delegate.writeAtomic(path, write)

    override fun move(
        from: String,
        to: String,
    ): Outcome<Unit> = delegate.move(from, to)

    override fun delete(path: String): Outcome<Unit> = delegate.delete(path)

    override fun deleteRecursively(path: String): Outcome<Unit> = delegate.deleteRecursively(path)

    override fun mkdirs(dir: String): Outcome<Unit> = delegate.mkdirs(dir)
}
