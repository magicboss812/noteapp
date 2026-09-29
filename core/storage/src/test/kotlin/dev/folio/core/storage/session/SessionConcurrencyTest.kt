package dev.folio.core.storage.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.ObjectId
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.storage.repo.RepositoryTestBase
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopyStore
import dev.folio.core.testing.ModelFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Regression for the P02 REVIEW pin race: autosave flushes running on other threads while edits land
 * must never unpin a page with newer unsaved edits (which, once evicted, would be written back stale).
 */
@RunWith(AndroidJUnit4::class)
class SessionConcurrencyTest : RepositoryTestBase() {
    @Test
    fun editsWhileFlushingOnOtherThreads_withTinyPageCache_allEditsPersist() =
        runBlocking<Unit> {
            val ref = documents.create(spec("Race", pages = 10)).orThrow()
            val appFs = fs.sub(".app")
            val io = Dispatchers.IO
            val real = FolioDispatchers(io, io, io, io, io)
            val scope = CoroutineScope(SupervisorJob() + io)
            val copy = WorkingCopyStore(appFs, fs).open(ref.path).orThrow()
            val doc = DocumentCodec.readDocument(copy).orThrow()
            val session =
                DocumentSession(
                    copy = copy,
                    initial = doc,
                    packer = Packer(fs, appFs, clock),
                    clock = clock,
                    dispatchers = real,
                    scope = scope,
                    app = ManifestApp("Folio", "test"),
                    thumbnails = { _, _ -> },
                    onPacked = {},
                    maxDecodedPages = 2,
                    autosaveDebounceMs = 1,
                )
            val pages = doc.pages.map { it.id }
            val random = Random(77)
            val flusher =
                scope.launch {
                    while (isActive) session.save()
                }
            repeat(EDITS) { i ->
                val page = pages[i % pages.size]
                session.execute(AddObjects(page, listOf(ModelFixtures.randomStroke(random, ObjectId("s$i"), points = 3)))).orThrow()
                if (i % 7 == 0) session.loadPages(listOf(pages[(i * 3) % pages.size])).orThrow()
            }
            flusher.cancel()
            session.close().orThrow()
            scope.cancel()

            val saved =
                FolioContainerReader.open(file(ref.path)).orThrow().use { DocumentCodec.readDocument(it, loadPages = true).orThrow() }
            val ids = saved.pageBodies.values.flatMap { page -> page.objects.map { it.id.value } }
            assertThat(ids).containsExactlyElementsIn(List(EDITS) { "s$it" })
        }

    private companion object {
        const val EDITS = 300
    }
}
