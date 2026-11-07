package dev.folio.feature.editor.text

import androidx.annotation.WorkerThread
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioFs
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.storage.di.AppFiles
import dev.folio.core.storage.library.LibraryRoot
import dev.folio.core.storage.library.StoragePermission
import dev.folio.core.text.fonts.CustomFontStore
import dev.folio.core.text.fonts.FontFileInfo
import dev.folio.core.text.fonts.FontLibrary
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.fonts.ImportedFont
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A picked font file; [open] returns null when it is gone. */
fun interface FontSource {
    /** Opens the file for reading. */
    @WorkerThread
    @Throws(IOException::class)
    fun open(): InputStream?
}

/**
 * Imported fonts for the editor (07-text-engine.md#fonts): the families the font picker lists, the import
 * of a picked `.ttf`/`.otf` file and the install of fonts found in the library folder on app start.
 */
@Singleton
class EditorFonts
    @Inject
    constructor(
        private val library: FontLibrary,
        private val dispatchers: FolioDispatchers,
    ) {
        /** Bundled and imported families; a new registry after every import or sync. */
        val registry: StateFlow<FontRegistry> get() = library.registry

        /** Installs the fonts the library folder has and app files lack (app start). */
        suspend fun sync(): Outcome<Int> = withContext(dispatchers.io) { library.sync() }

        /** Reads [source] (at most [FontFileInfo.MAX_FONT_BYTES]) and imports it. */
        suspend fun import(source: FontSource): Outcome<ImportedFont> =
            withContext(dispatchers.io) {
                when (val read = read(source)) {
                    is Outcome.Success -> library.import(read.value)
                    is Outcome.Failure -> read
                }
            }

        private fun read(source: FontSource): Outcome<ByteArray> =
            try {
                val bytes = source.open()?.use { it.readNBytes(FontFileInfo.MAX_FONT_BYTES + 1) }
                when {
                    bytes == null -> Outcome.Failure("font file not found")
                    bytes.size > FontFileInfo.MAX_FONT_BYTES -> Outcome.Failure("font file too large")
                    else -> Outcome.Success(bytes)
                }
            } catch (e: IOException) {
                FolioLog.w(TAG, "read font: ${e.message}", e)
                Outcome.Failure("font file unreadable", e)
            } catch (e: SecurityException) {
                // The picked document's provider revoked access.
                FolioLog.w(TAG, "read font: ${e.message}", e)
                Outcome.Failure("font file not readable", e)
            }

        private companion object {
            const val TAG = "EditorFonts"
        }
    }

/** Provides the [CustomFontStore] core:text builds its [FontLibrary] from (it needs core:storage's roots). */
@Module
@InstallIn(SingletonComponent::class)
internal object EditorFontsModule {
    @Provides
    @Singleton
    fun customFonts(
        @AppFiles appFs: FolioFs,
        root: LibraryRoot,
        permission: StoragePermission,
    ): CustomFontStore = CustomFontStore(appFs) { root.fs.takeIf { permission.isGranted() } }
}
