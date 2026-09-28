package dev.folio.core.model.edit

import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.Document
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import kotlin.random.Random

class UndoPropertyTest {
    @Test
    fun randomCommands_fullUndo_returnsOriginalAndRedoReturnsFinal() {
        for (seed in SEEDS) {
            val random = Random(seed)
            val original =
                ModelFixtures.document(
                    pages = List(3) { ModelFixtures.randomPage(random, objectCount = random.nextInt(6)) },
                    flows = listOf(ModelFixtures.flow("f1", "# Title\n\nSome *text*.")),
                )
            val clock = FakeClock()
            val undo = UndoManager(clock, capacity = COMMANDS)
            val generator = RandomCommands(random)
            var doc = original
            repeat(COMMANDS) { i ->
                val command = generator.next(doc)
                val applied = command.execute(doc)
                doc = applied.doc
                assertInvariant(doc, "seed=$seed step=$i command=$command")
                undo.push(applied)
                clock.advanceMs(2_000)
            }
            val final = doc
            while (true) doc = undo.undo(doc)?.doc ?: break
            assertWithMessage("seed=$seed full undo").that(doc).isEqualTo(original)
            while (true) doc = undo.redo(doc)?.doc ?: break
            assertWithMessage("seed=$seed full redo").that(doc).isEqualTo(final)
        }
    }

    private fun assertInvariant(
        doc: Document,
        msg: String,
    ) {
        for (ref in doc.pages) {
            val body = doc.pageBodies[ref.id] ?: continue
            assertWithMessage(msg).that(ref).isEqualTo(body.toRef())
        }
        assertWithMessage(msg).that(doc.pageBodies.keys).isEqualTo(doc.pages.map { it.id }.toSet())
    }

    private companion object {
        const val COMMANDS = 200
        val SEEDS = listOf(1L, 2L, 3L, 42L, 20260928L)
    }
}
