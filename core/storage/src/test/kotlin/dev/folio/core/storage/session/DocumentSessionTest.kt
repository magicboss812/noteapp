package dev.folio.core.storage.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.Document
import dev.folio.core.model.ObjectId
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.RemoveObjects
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.model.edit.UpdateMeta
import dev.folio.core.storage.repo.DocumentRef
import dev.folio.core.storage.repo.RepositoryTestBase
import dev.folio.core.storage.work.PackResult
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopyStore
import dev.folio.core.testing.ModelAssertions.assertPageEquivalent
import dev.folio.core.testing.ModelFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class DocumentSessionTest : RepositoryTestBase() {
    private val random = Random(909)
    private val appFs by lazy { fs.sub(".app") }

    private fun sessions(scope: CoroutineScope) =
        DocumentSessions(
            WorkingCopyStore(appFs, fs),
            Packer(fs, appFs, clock),
            scanner,
            clock,
            dispatchers,
            scope,
            ManifestApp("Folio", "test"),
        )

    @Test
    fun openEditCloseReopen_documentEqual() =
        runTest {
            val ref = documents.create(spec("Session", pages = 3)).orThrow()
            val first = sessions(backgroundScope).open(ref.path).orThrow()
            val page =
                first.document.value.pages[1]
                    .id
            first.loadPages(listOf(page)).orThrow()
            first.execute(AddObjects(page, List(5) { ModelFixtures.randomObject(random) })).orThrow()
            first.execute(UpdateFlows(listOf(ModelFixtures.flow("f1", "# Hello\n\nworld")))).orThrow()
            first
                .execute(
                    UpdateMeta(
                        first.document.value.meta
                            .copy(title = "Renamed inside"),
                    ),
                ).orThrow()
            first
                .loadPages(
                    first.document.value.pages
                        .map { it.id },
                ).orThrow()
            val edited = first.document.value

            assertThat(sessions(backgroundScope).close(first).orThrow()).isEqualTo(PackResult.Packed(ref.path))

            WorkingCopyStore(appFs, fs).discard(ref.docId).orThrow() // force reading the packed file
            val second = sessions(backgroundScope).open(ref.path).orThrow()
            second
                .loadPages(
                    second.document.value.pages
                        .map { it.id },
                ).orThrow()
            assertDocumentEquivalent(edited, second.document.value)
            assertThat(db.dao().document(ref.path)!!.title).isEqualTo("Renamed inside")
        }

    @Test
    fun loadPages_manyPages_holdsAtMost30AndEditsSurviveEviction() =
        runTest {
            val ref = documents.create(spec("Big", pages = 80)).orThrow()
            val session = sessions(backgroundScope).open(ref.path).orThrow()
            val ids =
                session.document.value.pages
                    .map { it.id }

            ids.forEach { id ->
                session.loadPages(listOf(id)).orThrow()
                assertThat(session.decodedPageCount).isAtMost(30)
            }
            ids.take(40).forEachIndexed { i, id ->
                session.execute(AddObjects(id, listOf(ModelFixtures.randomStroke(random, ObjectId("s$i"))))).orThrow()
            }
            session.save().orThrow()
            session.loadPages(listOf(ids.last())).orThrow()
            assertThat(session.decodedPageCount).isAtMost(30)

            // Page 0 was evicted after its edit was saved; undoing all edits reloads it from the working copy.
            repeat(40) { session.undo().orThrow() }
            session.loadPages(listOf(ids.first())).orThrow()
            assertThat(
                session.document.value
                    .page(ids.first())!!
                    .objects,
            ).isEmpty()
            session.redo().orThrow()
            assertThat(
                session.document.value
                    .page(ids.first())!!
                    .objects
                    .single()
                    .id,
            ).isEqualTo(ObjectId("s0"))
        }

    @Test
    fun close_triggersPack_untilThenFileUnchanged() =
        runTest {
            val ref = documents.create(spec("Pack")).orThrow()
            val before = file(ref.path).readBytes()
            val registry = sessions(backgroundScope)
            val session = registry.open(ref.path).orThrow()
            val page =
                session.document.value.pages
                    .single()
                    .id
            session.execute(AddObjects(page, listOf(ModelFixtures.randomStroke(random, ObjectId("x"))))).orThrow()
            session.save().orThrow()
            assertThat(file(ref.path).readBytes()).isEqualTo(before)

            registry.close(session).orThrow()

            assertThat(
                readFile(ref)
                    .pageBodies.values
                    .single()
                    .objects
                    .single()
                    .id,
            ).isEqualTo(ObjectId("x"))
        }

    @Test
    fun packTimer_packsAfter30SecondsWhileDirty() {
        val scope = TestScope()
        scope.runTest {
            val ref = documents.create(spec("Timer")).orThrow()
            val session = sessions(backgroundScope).open(ref.path).orThrow()
            val page =
                session.document.value.pages
                    .single()
                    .id
            session.execute(AddObjects(page, listOf(ModelFixtures.randomStroke(random, ObjectId("t"))))).orThrow()

            advanceTimeBy(29_000)
            runCurrent()
            assertThat(
                readFile(ref)
                    .pageBodies.values
                    .single()
                    .objects,
            ).isEmpty()
            advanceTimeBy(1_500)
            runCurrent()
            assertThat(
                readFile(ref)
                    .pageBodies.values
                    .single()
                    .objects,
            ).hasSize(1)
        }
    }

    @Test
    fun packAll_packsOpenSessions() =
        runTest {
            val ref = documents.create(spec("Stop")).orThrow()
            val registry = sessions(backgroundScope)
            val session = registry.open(ref.path).orThrow()
            assertThat(registry.open(ref.path).orThrow()).isSameInstanceAs(session)
            val page =
                session.document.value.pages
                    .single()
                    .id
            session.execute(AddObjects(page, listOf(ModelFixtures.randomStroke(random, ObjectId("s"))))).orThrow()

            registry.packAll()

            assertThat(
                readFile(ref)
                    .pageBodies.values
                    .single()
                    .objects,
            ).hasSize(1)
        }

    @Test
    fun execute_invalidCommand_failsAndKeepsDocument() =
        runTest {
            val ref = documents.create(spec("Invalid")).orThrow()
            val session = sessions(backgroundScope).open(ref.path).orThrow()
            val before = session.document.value
            val page = before.pages.single().id

            val result = session.execute(RemoveObjects(page, listOf(ObjectId("missing"))))

            assertThat(result).isInstanceOf(Outcome.Failure::class.java)
            assertThat(session.document.value.pages).isEqualTo(before.pages)
            assertThat(session.canUndo.value).isFalse()
        }

    private fun readFile(ref: DocumentRef): Document =
        FolioContainerReader.open(file(ref.path)).orThrow().use { DocumentCodec.readDocument(it, loadPages = true).orThrow() }

    private fun assertDocumentEquivalent(
        expected: Document,
        actual: Document,
    ) {
        assertThat(actual.meta.copy(modifiedMs = 0)).isEqualTo(expected.meta.copy(modifiedMs = 0))
        assertThat(actual.pages).isEqualTo(expected.pages)
        assertThat(actual.flows).isEqualTo(expected.flows)
        assertThat(actual.assets).isEqualTo(expected.assets)
        expected.pages.forEach { assertPageEquivalent(expected.pageBodies.getValue(it.id), actual.pageBodies.getValue(it.id)) }
    }
}
