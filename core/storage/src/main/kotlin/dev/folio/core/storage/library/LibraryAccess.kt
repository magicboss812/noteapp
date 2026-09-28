package dev.folio.core.storage.library

import android.os.Environment
import dev.folio.core.common.FolioFs
import dev.folio.core.common.JavaFileFolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.storage.LibraryConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** All-files access (09-storage-library.md#permission). */
fun interface StoragePermission {
    /** True if the app may use java.io on shared storage. */
    fun isGranted(): Boolean
}

/** [StoragePermission] backed by `Environment.isExternalStorageManager()`. */
class AndroidStoragePermission
    @Inject
    constructor() : StoragePermission {
        override fun isGranted(): Boolean = Environment.isExternalStorageManager()
    }

/** The library root directory: shared storage + [LibraryConfig.rootRelativePath]. */
class LibraryRoot(
    val dir: File,
) {
    /** File system rooted at the library folder. */
    val fs: FolioFs by lazy { JavaFileFolioFs(dir) }
}

/** Access state shown by the library entry screen. */
sealed interface LibraryAccessState {
    /** Not yet known (first check pending). */
    data object Checking : LibraryAccessState

    /** All-files access missing: show onboarding. */
    data object NeedsPermission : LibraryAccessState

    /** Library usable at [rootPath]. */
    data class Ready(
        val rootPath: String,
    ) : LibraryAccessState

    /** Access granted but the root could not be prepared. */
    data class Failed(
        val message: String,
    ) : LibraryAccessState
}

/** Checks the permission and prepares the library layout on grant. Blocking: io dispatcher. */
@Singleton
class LibraryAccess
    @Inject
    constructor(
        private val permission: StoragePermission,
        private val root: LibraryRoot,
    ) {
        /** Current state; creates the root and its hidden folders when access is granted. */
        fun check(): LibraryAccessState {
            if (!permission.isGranted()) return LibraryAccessState.NeedsPermission
            return when (val r = LibraryLayout.ensure(root.fs)) {
                is Outcome.Success -> LibraryAccessState.Ready(root.dir.path)
                is Outcome.Failure -> LibraryAccessState.Failed(r.message)
            }
        }

        /** Permission state without side effects (debug state reporting). */
        fun isGranted(): Boolean = permission.isGranted()

        /** Library root path. */
        val rootPath: String get() = root.dir.path

        /** Hidden folders that exist below the root (debug state reporting). */
        fun existingSystemFolders(): List<String> = LibraryLayout.SYSTEM_DIRS.filter { root.fs.exists(it) }
    }

/** Library folder layout (09-storage-library.md#library-layout). */
object LibraryLayout {
    /** Bin. */
    const val TRASH = ".trash"

    /** Custom templates. */
    const val TEMPLATES = ".templates"

    /** Folders created at the root when access is granted. */
    val SYSTEM_DIRS: List<String> = listOf(TRASH, TEMPLATES)

    /** Creates the root and [SYSTEM_DIRS] if missing. */
    fun ensure(fs: FolioFs): Outcome<Unit> {
        for (dir in SYSTEM_DIRS) {
            val r = fs.mkdirs(dir)
            if (r is Outcome.Failure) return r
        }
        return Outcome.Success(Unit)
    }

    /** Library root for [config] on this device's shared storage. */
    @Suppress("DEPRECATION") // getExternalStorageDirectory is the documented root for all-files access (ADR-004).
    fun rootFor(config: LibraryConfig): File = File(Environment.getExternalStorageDirectory(), config.rootRelativePath)
}
