package dev.folio.core.storage.work

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.storage.work.WorkFixture.orThrow
import dev.folio.core.testing.TempDirFolioFs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class WorkingCopyTest {
    @get:Rule val fs = TempDirFolioFs()

    private val source = "Physics.folio"

    @Test
    fun open_unpacksAllEntries_andReadsBackTheDocument() {
        val doc = WorkFixture.document()
        WorkFixture.writeFolio(fs.sub("library"), source, doc)
        val copy = WorkingCopyStore(fs.sub("app"), fs.sub("library")).open(source).orThrow()
        assertThat(copy.names()).containsAtLeast("mimetype", "manifest.json", "search/text.txt")
        assertThat(copy.names()).doesNotContain("base.json")
        val read = DocumentCodec.readDocument(copy, loadPages = true).orThrow()
        assertThat(read.pages).isEqualTo(doc.pages)
        assertThat(copy.base.docId).isEqualTo(doc.meta.id.value)
        assertThat(copy.isDirty).isFalse()
    }

    @Test
    fun open_unchangedSource_reusesCopy_changedCleanSource_unpacksFresh() {
        val library = fs.sub("library")
        val store = WorkingCopyStore(fs.sub("app"), library)
        val doc = WorkFixture.document()
        WorkFixture.writeFolio(library, source, doc)
        val first = store.open(source).orThrow()
        first.writeEntries(mapOf("thumbs/marker.webp" to byteArrayOf(1))).orThrow()
        first.updateBase(first.base.copy(dirtyEntries = emptySet())).orThrow()
        assertThat(store.open(source).orThrow().names()).contains("thumbs/marker.webp")

        WorkFixture.writeFolio(library, source, doc.copy(meta = doc.meta.copy(title = "Changed title, longer")))
        assertThat(store.open(source).orThrow().names()).doesNotContain("thumbs/marker.webp")
    }

    @Test
    fun open_manualDuplicate_getsNewDocIdAndOwnCopy() {
        val library = fs.sub("library")
        val store = WorkingCopyStore(fs.sub("app"), library)
        val doc = WorkFixture.document()
        WorkFixture.writeFolio(library, source, doc)
        java.io.File(fs.root, "library/$source").copyTo(java.io.File(fs.root, "library/Copy.folio"))
        val original = store.open(source).orThrow()
        original.writeEntries(mapOf("flows/x.md" to "mine".toByteArray())).orThrow()

        val duplicate = store.open("Copy.folio").orThrow()

        assertThat(duplicate.docId).isNotEqualTo(original.docId)
        assertThat(duplicate.names()).doesNotContain("flows/x.md")
        assertThat(store.find(original.docId)!!.base.sourcePath).isEqualTo(source)
        assertThat(store.find(original.docId)!!.names()).contains("flows/x.md")
        assertThat(store.open("Copy.folio").orThrow().docId).isEqualTo(duplicate.docId)
    }

    @Test
    fun open_dirtyCopyOfMovedFile_followsTheFile() {
        val library = fs.sub("library")
        val store = WorkingCopyStore(fs.sub("app"), library)
        WorkFixture.writeFolio(library, source, WorkFixture.document())
        val copy = store.open(source).orThrow()
        copy.writeEntries(mapOf("flows/x.md" to "unsaved".toByteArray())).orThrow()
        library.move(source, "Moved.folio").orThrow()

        val reopened = store.open("Moved.folio").orThrow()

        assertThat(reopened.docId).isEqualTo(copy.docId)
        assertThat(reopened.base.sourcePath).isEqualTo("Moved.folio")
        assertThat(reopened.names()).contains("flows/x.md")
    }

    @Test
    fun open_missingSource_fails() {
        assertThat(WorkingCopyStore(fs.sub("app"), fs.sub("library")).open("nope.folio")).isInstanceOf(Outcome.Failure::class.java)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun autosaver_burstOfChanges_writesOnceAfterOneSecondIdle() =
        runTest {
            val writes = mutableListOf<Map<String, ByteArray?>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val saver =
                EntryAutosaver(backgroundScope, dispatcher) { batch ->
                    writes += batch
                    Outcome.Success(Unit)
                }
            var text = ""
            repeat(3) { i ->
                text += "$i"
                saver.schedule("flows/f.md") { text.toByteArray() }
                advanceTimeBy(500)
            }
            runCurrent()
            assertThat(writes).isEmpty()
            advanceTimeBy(600)
            runCurrent()
            assertThat(writes).hasSize(1)
            assertThat(writes.single().getValue("flows/f.md")!!.decodeToString()).isEqualTo("012")
            assertThat(saver.hasPending).isFalse()
        }

    @Test
    fun autosaver_flush_writesImmediately() =
        runTest {
            val writes = mutableListOf<Map<String, ByteArray?>>()
            val saver =
                EntryAutosaver(backgroundScope, StandardTestDispatcher(testScheduler)) {
                    writes += it
                    Outcome.Success(Unit)
                }
            saver.schedule("pages/p.pb") { null }
            saver.flush()
            assertThat(writes.single()).containsExactly("pages/p.pb", null)
        }
}
