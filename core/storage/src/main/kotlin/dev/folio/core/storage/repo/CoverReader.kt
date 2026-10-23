package dev.folio.core.storage.repo

import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioFs
import dev.folio.core.common.getOrNull
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.container.FolioEntries
import kotlinx.coroutines.withContext

/** Reads the stored cover thumbnail (`thumbs/cover.webp`) straight from a `.folio` file for the library cards. */
class CoverReader(
    private val fs: FolioFs,
    private val dispatchers: FolioDispatchers,
) {
    /** WebP bytes of the cover of the document at library [path]; null when it has none or cannot be read. */
    suspend fun read(path: String): ByteArray? =
        withContext(dispatchers.io) {
            val local = fs.localFile(path) ?: return@withContext null
            FolioContainerReader.open(local).getOrNull()?.use { reader ->
                if (FolioEntries.COVER in reader.names()) reader.read(FolioEntries.COVER, MAX_COVER_BYTES).getOrNull() else null
            }
        }

    private companion object {
        const val MAX_COVER_BYTES = 4L * 1024 * 1024
    }
}
