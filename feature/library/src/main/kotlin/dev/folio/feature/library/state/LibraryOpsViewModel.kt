package dev.folio.feature.library.state

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.storage.repo.CoverReader
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.LibraryRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Note menu and batch actions (through [LibraryDocOps]) and the cover thumbnails of the cards. Snackbar texts arrive
 * on [messages]; covers are decoded once per file version and kept in a small LRU.
 */
@Suppress("TooManyFunctions") // one facade for the note and folder menus; each function hands off to an ops class
@HiltViewModel
class LibraryOpsViewModel
    @Inject
    constructor(
        documents: Lazy<DocumentRepository>,
        library: Lazy<LibraryRepository>,
        private val covers: Lazy<CoverReader>,
        private val dispatchers: FolioDispatchers,
    ) : ViewModel() {
        private val ops by lazy { LibraryDocOps(documents.get(), library.get()) }
        private val folderOps by lazy { LibraryFolderOps(library.get()) }
        private val mutableMessages = Channel<String>(Channel.BUFFERED)
        private val decoded = LruCache<String, ImageBitmap>(COVER_CACHE_SIZE)

        /** Snackbar texts, one per finished action that has something to say. */
        val messages: Flow<String> = mutableMessages.receiveAsFlow()

        /** Renames the note at [path]. */
        fun rename(
            path: String,
            title: String,
        ) = run { ops.rename(path, title) }

        /** Moves [paths] into [folder]. */
        fun move(
            paths: Collection<String>,
            folder: String,
        ) = run { ops.move(paths.toList(), folder) }

        /** Duplicates the note at [path]. */
        fun duplicate(path: String) = run { ops.duplicate(path) }

        /** Sets the favorite mark of [paths]. */
        fun setFavorite(
            paths: Collection<String>,
            favorite: Boolean,
        ) = run { ops.setFavorite(paths.toList(), favorite) }

        /** Replaces the tags of the note at [path]. */
        fun setTags(
            path: String,
            tags: List<String>,
        ) = run { ops.setTags(path, tags) }

        /** Moves [paths] to the bin. */
        fun delete(paths: Collection<String>) = run { ops.delete(paths.toList()) }

        /** Creates folder [name] inside [parent] ("" = root). */
        fun createFolder(
            parent: String,
            name: String,
        ) = run { folderOps.create(parent, name) }

        /** Renames the folder at [path]. */
        fun renameFolder(
            path: String,
            name: String,
        ) = run { folderOps.rename(path, name) }

        /** Sets the color of the folder at [path]. */
        fun setFolderTint(
            path: String,
            tint: String?,
        ) = run { folderOps.setTint(path, tint) }

        /** Moves the folder at [path] into [parent]. */
        fun moveFolder(
            path: String,
            parent: String,
        ) = run { folderOps.move(path, parent) }

        /** Moves the folder at [path] to the bin. */
        fun deleteFolder(path: String) = run { folderOps.delete(path) }

        /** The decoded cover of [doc], or null when it has none or the file cannot be read. */
        suspend fun cover(doc: LibraryDocItem): ImageBitmap? {
            if (!doc.hasCover) return null
            val key = "${doc.path}@${doc.modifiedMs}"
            decoded.get(key)?.let { return it }
            return withContext(dispatchers.io) {
                covers.get().read(doc.path)?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }?.also { decoded.put(key, it) }
        }

        private fun run(action: suspend () -> String?) {
            viewModelScope.launch { action()?.let { mutableMessages.send(it) } }
        }

        private companion object {
            const val COVER_CACHE_SIZE = 24
        }
    }
