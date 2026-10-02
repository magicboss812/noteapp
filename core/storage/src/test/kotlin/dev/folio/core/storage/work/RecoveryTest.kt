package dev.folio.core.storage.work

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.FolioEntries
import dev.folio.core.storage.work.WorkFixture.orThrow
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.FaultyFolioFs
import dev.folio.core.testing.TempDirFolioFs
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

class RecoveryTest {
    @get:Rule val fs = TempDirFolioFs()

    private val clock = FakeClock(1_790_000_000_000L) // 2026-09-21T14:13:20Z
    private val source = "School/Physics.folio"
    private val events = RecoveryEvents()
    private val doc = WorkFixture.document()
    private lateinit var store: WorkingCopyStore
    private lateinit var recovery: Recovery

    @Before
    fun setUp() {
        val library = fs.sub("library")
        val app = fs.sub("app")
        WorkFixture.writeFolio(library, source, doc)
        store = WorkingCopyStore(app, library)
        recovery = Recovery(store, Packer(library, app, clock, ZoneOffset.UTC), clock, events)
    }

    @Test
    fun recovery_dirtyCopy_isPackedIntoSource() {
        dirtyCopy("# Recovered text")
        val result = recovery.run()
        assertThat(result).containsExactly(RecoveryEvent.Recovered(doc.meta.id, source))
        assertThat(events.pending.value).isEqualTo(result)
        val flowId = doc.flows.keys.first()
        assertThat(
            WorkFixture
                .readFolio(libraryFile(source))
                .flows
                .getValue(flowId)
                .markdown,
        ).isEqualTo("# Recovered text")
        assertThat(recovery.run()).isEmpty()
    }

    @Test
    fun recovery_sourceChangedExternally_createsConflictCopy() {
        dirtyCopy("# Mine")
        val external = WorkFixture.document(seed = 9).let { it.copy(meta = doc.meta.copy(title = "External")) }
        WorkFixture.writeFolio(fs.sub("library"), source, external)
        val externalBytes = libraryFile(source).readBytes()

        val result = recovery.run()

        val conflict = "School/Physics- Week 1 (conflict 2026-09-21 14-13).folio"
        assertThat(result).containsExactly(RecoveryEvent.Conflict(doc.meta.id, conflict))
        assertThat(libraryFile(source).readBytes()).isEqualTo(externalBytes)
        val flowId = doc.flows.keys.first()
        assertThat(
            WorkFixture
                .readFolio(libraryFile(conflict))
                .flows
                .getValue(flowId)
                .markdown,
        ).isEqualTo("# Mine")
    }

    @Test
    fun recovery_sourceDeleted_keepsOrphan7DaysFromFirstReport() {
        // The document was last modified months before the edit: that age must not count.
        dirtyCopy("# Lost")
        libraryFile(source).delete()
        assertThat(recovery.run()).containsExactly(RecoveryEvent.Orphaned(doc.meta.id, "Physics: Week 1"))
        clock.advanceMs(6L * DAY_MS)
        assertThat(recovery.run()).containsExactly(RecoveryEvent.Orphaned(doc.meta.id, "Physics: Week 1"))
        assertThat(store.find(doc.meta.id)).isNotNull()
        clock.advanceMs(2L * DAY_MS)
        assertThat(recovery.run()).isEmpty()
        assertThat(store.find(doc.meta.id)).isNull()
    }

    @Test
    fun writeEntries_crashAfterDirtyMark_isStillRecovered() {
        val app = FaultyFolioFs(fs.sub("app"), ".md")
        val faultyStore = WorkingCopyStore(app, fs.sub("library"))
        val copy = faultyStore.open(source).orThrow()
        val flow = doc.flows.values.first()
        app.failAfterBytes = 0
        assertThat(copy.writeEntries(mapOf("flows/${flow.id.value}.md" to "# x".toByteArray()))).isInstanceOf(Outcome.Failure::class.java)
        assertThat(faultyStore.find(doc.meta.id)!!.isDirty).isTrue()
        assertThat(recovery.run()).containsExactly(RecoveryEvent.Recovered(doc.meta.id, source))
    }

    @Test
    fun recovery_onlyThumbnailsDirty_packsWithoutEvent() {
        store
            .open(source)
            .orThrow()
            .writeEntries(mapOf(FolioEntries.COVER to byteArrayOf(1)))
            .orThrow()

        assertThat(recovery.run()).isEmpty()

        assertThat(store.find(doc.meta.id)!!.isDirty).isFalse()
        assertThat(events.pending.value).isEmpty()
    }

    @Test
    fun events_consume_removesEvent() {
        dirtyCopy("x")
        val event = recovery.run().single()
        events.consume(event)
        assertThat(events.pending.value).isEmpty()
    }

    private fun dirtyCopy(markdown: String) {
        val copy = store.open(source).orThrow()
        val flow = doc.flows.values.first()
        copy.writeEntries(mapOf("flows/${flow.id.value}.md" to markdown.toByteArray())).orThrow()
    }

    private fun libraryFile(path: String) = File(fs.root, "library/$path")

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
