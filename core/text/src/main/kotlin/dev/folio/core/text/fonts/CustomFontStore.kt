package dev.folio.core.text.fonts

import androidx.annotation.WorkerThread
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.folio.core.common.FolioFs
import dev.folio.core.common.FolioLog
import dev.folio.core.common.FsEntry
import dev.folio.core.common.Outcome
import dev.folio.core.common.map

/** One imported font file in app storage. */
data class ImportedFont(
    val fileName: String,
    val info: FontFileInfo,
)

/**
 * Imported `.ttf`/`.otf` fonts (07-text-engine.md#fonts). The working copy lives in app files
 * ([APP_DIR]); every import is mirrored into the library folder ([LIBRARY_DIR]) so another device
 * sharing the library installs it with [syncFromLibrary]. Files are named after their family and
 * face, so importing the same face again replaces it. Blocking: call on the io dispatcher.
 */
class CustomFontStore(
    /** File system rooted at the app files directory. */
    private val appFs: FolioFs,
    /** File system rooted at the library folder, or null while library access is missing. */
    private val libraryFs: () -> FolioFs?,
) {
    /** Validates [bytes], stores them in app files and mirrors them into the library folder. */
    @WorkerThread
    fun import(bytes: ByteArray): Outcome<ImportedFont> {
        val info =
            when (val parsed = FontFileInfo.parse(bytes)) {
                is Outcome.Success -> parsed.value
                is Outcome.Failure -> return parsed
            }
        val font = ImportedFont(fileNameFor(info), info)
        val written = appFs.writeBytesAtomic("$APP_DIR/${font.fileName}", bytes)
        if (written is Outcome.Failure) return written
        val library = libraryFs()
        if (library != null) {
            val mirrored = library.writeBytesAtomic("$LIBRARY_DIR/${font.fileName}", bytes)
            if (mirrored is Outcome.Failure) FolioLog.w(TAG, "mirror ${font.fileName}: ${mirrored.message}", mirrored.cause)
        }
        return Outcome.Success(font)
    }

    /**
     * Installs fonts found in the library [LIBRARY_DIR] that app files lack (imported on another
     * device or copied there by hand). Invalid files are skipped. Returns the number installed.
     */
    @WorkerThread
    fun syncFromLibrary(): Outcome<Int> {
        val library = libraryFs() ?: return Outcome.Success(0)
        if (!library.exists(LIBRARY_DIR)) return Outcome.Success(0)
        val entries =
            when (val listed = library.list(LIBRARY_DIR)) {
                is Outcome.Success -> listed.value
                is Outcome.Failure -> return listed
            }
        var installed = 0
        for (entry in entries.filter(::isLibraryCandidate)) {
            when (val r = installFromLibrary(library, entry)) {
                is Outcome.Success -> if (r.value) installed++
                is Outcome.Failure -> return r
            }
        }
        return Outcome.Success(installed)
    }

    /** A font file in the library that app files lack under the same name and size. */
    private fun isLibraryCandidate(entry: FsEntry): Boolean =
        !entry.isDirectory &&
            isFontFileName(entry.path) &&
            entry.sizeBytes <= FontFileInfo.MAX_FONT_BYTES &&
            appFs.stat("$APP_DIR/${entry.path.substringAfterLast('/')}")?.sizeBytes != entry.sizeBytes

    /** Copies one library font into app files; true if installed, false if invalid or already present. */
    private fun installFromLibrary(
        library: FolioFs,
        entry: FsEntry,
    ): Outcome<Boolean> {
        val bytes = (library.readBytes(entry.path) as? Outcome.Success)?.value
        val info = bytes?.let { (FontFileInfo.parse(it) as? Outcome.Success)?.value }
        if (bytes == null || info == null) {
            FolioLog.w(TAG, "skip library font ${entry.path}")
            return Outcome.Success(false)
        }
        val target = "$APP_DIR/${fileNameFor(info)}"
        if (appFs.stat(target)?.sizeBytes == entry.sizeBytes) return Outcome.Success(false)
        return appFs.writeBytesAtomic(target, bytes).map { true }
    }

    /** Imported fonts in app files, sorted by file name; unreadable files are skipped. */
    @WorkerThread
    fun installed(): List<ImportedFont> {
        if (!appFs.exists(APP_DIR)) return emptyList()
        val entries = (appFs.list(APP_DIR) as? Outcome.Success)?.value.orEmpty()
        return entries
            .filter { !it.isDirectory && isFontFileName(it.path) && it.sizeBytes <= FontFileInfo.MAX_FONT_BYTES }
            .sortedBy { it.path }
            .mapNotNull { entry ->
                val info = (appFs.readBytes(entry.path) as? Outcome.Success)?.value?.let { FontFileInfo.parse(it) }
                (info as? Outcome.Success)?.let { ImportedFont(entry.path.substringAfterLast('/'), it.value) }
            }
    }

    /** One family per imported family name, each face mapped to its weight and style. */
    @WorkerThread
    fun families(): List<TextFontFamily> =
        installed()
            .groupBy { it.info.familyName }
            .mapNotNull { (name, faces) ->
                val fonts =
                    faces.mapNotNull { face ->
                        val file = appFs.localFile("$APP_DIR/${face.fileName}") ?: return@mapNotNull null
                        Font(
                            file,
                            if (face.info.bold) FontWeight.Bold else FontWeight.Normal,
                            if (face.info.italic) FontStyle.Italic else FontStyle.Normal,
                        )
                    }
                if (fonts.isEmpty()) null else TextFontFamily(name, FontGroup.IMPORTED, FontFamily(fonts))
            }

    /** Registry with the bundled and the installed imported families. */
    @WorkerThread
    fun registry(): FontRegistry = FontRegistry(families())

    companion object {
        /** Imported fonts below the app files root. */
        const val APP_DIR = "fonts"

        /** Mirror below the library root (09-storage-library.md#library-layout). */
        const val LIBRARY_DIR = ".fonts"

        private const val TAG = "Fonts"

        /** Canonical file name: family and face, e.g. "Fira Sans-BoldItalic.ttf". */
        fun fileNameFor(info: FontFileInfo): String {
            val family = info.familyName.map { if (it.isLetterOrDigit() || it == ' ' || it == '_') it else '-' }.joinToString("")
            val face =
                when {
                    info.bold && info.italic -> "BoldItalic"
                    info.bold -> "Bold"
                    info.italic -> "Italic"
                    else -> "Regular"
                }
            return "$family-$face.${info.extension}"
        }

        private fun isFontFileName(path: String): Boolean =
            path.endsWith(".ttf", ignoreCase = true) || path.endsWith(".otf", ignoreCase = true)
    }
}
