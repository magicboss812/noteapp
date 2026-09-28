package dev.folio.core.model.edit

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowId
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UndoManagerTest {
    private val clock = FakeClock()
    private val base = ModelFixtures.document(listOf(ModelFixtures.page("p1")), flows = listOf(ModelFixtures.flow("f", "")))

    @Test
    fun capacity_exceeded_dropsOldest() {
        val undo = UndoManager(clock, capacity = 3)
        var doc = base
        repeat(5) { i ->
            val applied = type(doc, "$i", key = null)
            doc = applied.doc
            undo.push(applied)
        }
        assertThat(undo.undoDepth).isEqualTo(3)
        while (true) doc = undo.undo(doc)?.doc ?: break
        // The two oldest entries ("0", "1") could not be undone.
        assertThat(doc.flows.getValue(FlowId("f")).markdown).isEqualTo("01")
    }

    @Test
    fun coalescing_typingBurstWithSameKey_isOneEntry() {
        val undo = UndoManager(clock)
        var doc = base
        "hello".forEach { ch ->
            val applied = type(doc, ch.toString(), key = "flow:f")
            doc = applied.doc
            undo.push(applied)
            clock.advanceMs(300)
        }
        assertThat(undo.undoDepth).isEqualTo(1)
        doc = undo.undo(doc)!!.doc
        assertThat(doc).isEqualTo(base)
        doc = undo.redo(doc)!!.doc
        assertThat(doc.flows.getValue(FlowId("f")).markdown).isEqualTo("hello")
    }

    @Test
    fun coalescing_gapOver1000Ms_startsNewEntry() {
        val undo = UndoManager(clock)
        var doc = base
        listOf(0L, 999L, 1001L).forEach { gap ->
            clock.advanceMs(gap)
            val applied = type(doc, "a", key = "flow:f")
            doc = applied.doc
            undo.push(applied)
        }
        assertThat(undo.undoDepth).isEqualTo(2)
    }

    @Test
    fun coalescing_differentKeyOrAfterUndo_doesNotMerge() {
        val undo = UndoManager(clock)
        var doc = base
        doc = type(doc, "a", "k1").also { undo.push(it) }.doc
        doc = type(doc, "b", "k2").also { undo.push(it) }.doc
        assertThat(undo.undoDepth).isEqualTo(2)
        doc = undo.undo(doc)!!.doc
        doc = type(doc, "c", "k1").also { undo.push(it) }.doc
        assertThat(undo.undoDepth).isEqualTo(2)
        assertThat(doc.flows.getValue(FlowId("f")).markdown).isEqualTo("ac")
    }

    @Test
    fun push_afterUndo_clearsRedo() =
        runTest {
            val undo = UndoManager(clock)
            undo.canRedo.test {
                assertThat(awaitItem()).isFalse()
                var doc = type(base, "a", null).also { undo.push(it) }.doc
                doc = undo.undo(doc)!!.doc
                assertThat(awaitItem()).isTrue()
                type(doc, "b", null).also { undo.push(it) }
                assertThat(awaitItem()).isFalse()
                assertThat(undo.redo(doc)).isNull()
            }
        }

    @Test
    fun canUndo_tracksStack() =
        runTest {
            val undo = UndoManager(clock)
            undo.canUndo.test {
                assertThat(awaitItem()).isFalse()
                val doc = type(base, "a", null).also { undo.push(it) }.doc
                assertThat(awaitItem()).isTrue()
                undo.undo(doc)
                assertThat(awaitItem()).isFalse()
            }
        }

    private fun type(
        doc: dev.folio.core.model.Document,
        text: String,
        key: String?,
    ): Applied {
        val length =
            doc.flows
                .getValue(FlowId("f"))
                .markdown.length
        return EditFlow(FlowId("f"), listOf(TextEdit(length, length, text)), key).execute(doc)
    }
}
