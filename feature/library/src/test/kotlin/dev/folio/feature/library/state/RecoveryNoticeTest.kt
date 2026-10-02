package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.DocId
import dev.folio.core.storage.work.RecoveryEvent
import dev.folio.core.storage.work.RecoveryEvents
import org.junit.Test

class RecoveryNoticeTest {
    private val a = DocId("a")
    private val b = DocId("b")

    @Test
    fun nothingPending_noMessage() {
        assertThat(recoveryMessage(emptyList())).isNull()
    }

    @Test
    fun severalRecovered_oneMessage() {
        val events = listOf(RecoveryEvent.Recovered(a, "One.folio"), RecoveryEvent.Recovered(b, "Two.folio"))

        assertThat(recoveryMessage(events)).isEqualTo("Recovered unsaved changes.")
    }

    @Test
    fun notice_carriesExactlyTheEventsItReports() {
        val events = listOf(RecoveryEvent.Recovered(a, "One.folio"))

        assertThat(recoveryNotice(events)).isEqualTo(RecoveryNotice("Recovered unsaved changes.", events))
        assertThat(recoveryNotice(emptyList())).isNull()
    }

    @Test
    fun noticeShown_consumesOnlyItsEvents() {
        val pending = RecoveryEvents()
        val first = RecoveryEvent.Recovered(a, "One.folio")
        pending.publish(listOf(first))
        val shown = recoveryNotice(pending.pending.value)!!
        val later = RecoveryEvent.Conflict(b, "Two (conflict).folio")
        pending.publish(listOf(later))

        shown.events.forEach(pending::consume)

        assertThat(pending.pending.value).containsExactly(later)
    }

    @Test
    fun conflictAndOrphan_namedInTheMessage() {
        val events =
            listOf(
                RecoveryEvent.Conflict(a, "Notes/Plan (conflict 2026-10-02 09-00).folio"),
                RecoveryEvent.Orphaned(b, "Lost note"),
            )

        assertThat(recoveryMessage(events))
            .isEqualTo("Saved a conflict copy: Plan (conflict 2026-10-02 09-00). Recovered: Lost note.")
    }
}
