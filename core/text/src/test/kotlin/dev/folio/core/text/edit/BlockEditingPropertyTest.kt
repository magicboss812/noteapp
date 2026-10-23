package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.markdown.SpanKind
import org.junit.Test
import kotlin.random.Random

/**
 * Random sequences of edits over flows with every block kind. After each step the derived block structure must be what
 * parsing the flow text gives (undo and a reopen re-parse), ids stay unique and the selection stays inside the blocks.
 */
class BlockEditingPropertyTest {
    private val fixtures =
        listOf(
            "",
            "a",
            "\n\n\nx",
            "# Title\n\nParagraph one with **bold** and \$x^2\$.\n\n- a\n- b\n  - c\n- d\n\n1. one\n2. two\n\n> quote\n> more\n\n" +
                "```kotlin\nval x = 1\n```\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n\$\$\nx^2\n\$\$\n\n---\n\nlast",
            "- [ ] one\n- [x] two\n\ntext  \nhard break\n\n## H2\nparagraph right below a heading",
        )
    private val snippets =
        listOf(
            "x",
            " ",
            "\n",
            "\n\n",
            "- ",
            "1. ",
            "> ",
            "# ",
            "| a | b |",
            "[] ",
            "  ",
            "**",
            "---",
            "- [ ] t",
            "```",
            "\$\$",
            "\n- i",
            "\n\n> q",
        )
    private val kinds = listOf(SpanKind.BOLD, SpanKind.ITALIC, SpanKind.STRIKE, SpanKind.HIGHLIGHT, SpanKind.CODE, SpanKind.MATH)

    private class State(
        var doc: MarkdownDocument,
        var selection: TextSelection,
    )

    @Test
    fun randomEdits_keepStructureEqualToAReparse() {
        for (seed in 0 until SEEDS) {
            val random = Random(seed)
            val fixture = fixtures[seed % fixtures.size]
            val doc = BlockEditing.ensureBlock(MarkdownDocument.parse(fixture))
            val state =
                State(
                    doc,
                    TextSelection.at(
                        Caret(
                            doc.blocks.last().id,
                            doc.blocks
                                .last()
                                .source.length,
                        ),
                    ),
                )
            val log = ArrayList<String>()
            repeat(STEPS) { step ->
                val before = state.doc.toMarkdown()
                val name = step(state, random)
                log += "$step $name"
                check(seed, log, before, state)
            }
        }
    }

    private fun check(
        seed: Int,
        log: List<String>,
        before: String,
        state: State,
    ) {
        val doc = state.doc
        val context = "seed $seed ops ${log.takeLast(CONTEXT_OPS)} before=${before.escaped()} after=${doc.toMarkdown().escaped()}"
        assertWithMessage("unique ids, $context")
            .that(
                doc.blocks
                    .map { it.id }
                    .toSet()
                    .size,
            ).isEqualTo(doc.blocks.size)
        for (caret in listOf(state.selection.anchor, state.selection.focus)) {
            val index = doc.indexOf(caret.blockId)
            assertWithMessage("caret block, $context").that(index).isAtLeast(0)
            assertWithMessage("caret offset, $context").that(caret.offset).isIn(0..doc.blocks[index].source.length)
        }
        // The text "" parses to no block; the editor keeps one empty paragraph for the caret.
        val reparsed = if (doc.toMarkdown().isEmpty()) listOf("") else doc.reparsedSources()
        if (doc.blocks.none { unterminated(it.source) }) {
            assertWithMessage("structure, $context").that(doc.blocks.map { it.source }).isEqualTo(reparsed)
        }
    }

    /** Runs one random edit on [state]; returns its name for the failure log. A null result means the edit did not apply. */
    private fun step(
        state: State,
        random: Random,
    ): String {
        val op = random.nextInt(OPS)
        if (op == MOVE) return move(state, random)
        val step = if (op < TEXT_OPS) textStep(op, state, random) else formatStep(op, state, random)
        step.result?.let {
            state.doc = it.document
            state.selection = it.selection
        }
        return step.name
    }

    private class Step(
        val name: String,
        val result: EditResult?,
    )

    private fun textStep(
        op: Int,
        state: State,
        random: Random,
    ): Step {
        val doc = state.doc
        val selection = state.selection
        return when (op) {
            0, 1 -> {
                val text = snippets[random.nextInt(snippets.size)]
                Step("insert(${text.escaped()})", BlockEditing.insert(doc, selection, text))
            }

            2 -> {
                Step("enter", BlockEditing.enter(doc, selection))
            }

            3 -> {
                Step("softBreak", BlockEditing.softBreak(doc, selection))
            }

            4 -> {
                Step("backspace", BlockEditing.backspace(doc, selection) ?: fieldDelete(doc, selection.focus, -1))
            }

            else -> {
                Step("deleteForward", BlockEditing.deleteForward(doc, selection) ?: fieldDelete(doc, selection.focus, 1))
            }
        }
    }

    private fun formatStep(
        op: Int,
        state: State,
        random: Random,
    ): Step {
        val doc = state.doc
        val caret = state.selection.focus
        return when (op) {
            6 -> {
                val outdent = random.nextBoolean()
                Step("indent(outdent=$outdent)", BlockEditing.indent(doc, caret, outdent))
            }

            7 -> {
                val style = BlockStyle.entries[random.nextInt(BlockStyle.entries.size)]
                Step("setStyle($style)", BlockStyles.apply(doc, caret, style))
            }

            8 -> {
                val kind = kinds[random.nextInt(kinds.size)]
                Step("toggleInline($kind)", Formatting.toggleInline(doc, withinBlock(doc, caret, random), kind))
            }

            9 -> {
                Step("insertTable", Formatting.insertTable(doc, caret, 1 + random.nextInt(3)))
            }

            10 -> {
                cutAndPaste(doc, random)
            }

            11 -> {
                Step("link", Formatting.link(doc, withinBlock(doc, caret, random)))
            }

            else -> {
                error("op $op")
            }
        }
    }

    private fun cutAndPaste(
        doc: MarkdownDocument,
        random: Random,
    ): Step {
        val range = randomSelection(doc, random)
        val text = BlockEditing.copy(doc, range)
        val cut = BlockEditing.delete(doc, range)
        return Step("cut+paste", BlockEditing.insert(cut.document, TextSelection.at(randomCaret(cut.document, random)), text))
    }

    /** What the live field does for a plain Backspace (-1) or Delete (+1) inside a block: removes one character. */
    private fun fieldDelete(
        doc: MarkdownDocument,
        caret: Caret,
        direction: Int,
    ): EditResult? {
        val source = doc.blocks[doc.indexOf(caret.blockId)].source
        val at = if (direction < 0) caret.offset - 1 else caret.offset
        if (at < 0 || at >= source.length) return null
        return BlockEditing.setSource(doc, caret.blockId, source.removeRange(at, at + 1), at)
    }

    private fun move(
        state: State,
        random: Random,
    ): String {
        val doc = state.doc
        val caret = state.selection.focus
        val next = if (random.nextBoolean()) BlockEditing.left(doc, caret) else BlockEditing.right(doc, caret)
        state.selection = TextSelection.at(next ?: randomCaret(doc, random))
        return "move"
    }

    private fun randomCaret(
        doc: MarkdownDocument,
        random: Random,
    ): Caret {
        val block = doc.blocks[random.nextInt(doc.blocks.size)]
        return Caret(block.id, random.nextInt(block.source.length + 1))
    }

    private fun randomSelection(
        doc: MarkdownDocument,
        random: Random,
    ) = TextSelection(randomCaret(doc, random), randomCaret(doc, random))

    private fun withinBlock(
        doc: MarkdownDocument,
        caret: Caret,
        random: Random,
    ): TextSelection {
        val length = doc.blocks[doc.indexOf(caret.blockId)].source.length
        val other = random.nextInt(length + 1)
        return TextSelection(Caret(caret.blockId, caret.offset.coerceAtMost(length)), Caret(caret.blockId, other))
    }

    /** True for a block that opens a fence or `$$` without closing it: a parse of the whole flow lets it swallow what follows. */
    private fun unterminated(source: String): Boolean {
        val lines = source.lines()
        val first = lines.first().trimStart()
        val last = lines.last().trim()
        return when {
            first.startsWith("```") || first.startsWith("~~~") -> {
                lines.size == 1 || !last.startsWith(first.take(FENCE))
            }

            first.startsWith("\$\$") -> {
                !(first.trimEnd().length > FENCE && first.trimEnd().endsWith("\$\$")) &&
                    (lines.size == 1 || !last.endsWith("\$\$"))
            }

            else -> {
                false
            }
        }
    }

    private fun String.escaped() = replace("\n", "\\n")

    private companion object {
        const val SEEDS = 400
        const val STEPS = 25
        const val OPS = 13
        const val TEXT_OPS = 6
        const val MOVE = 12
        const val CONTEXT_OPS = 6
        const val FENCE = 3
    }
}
