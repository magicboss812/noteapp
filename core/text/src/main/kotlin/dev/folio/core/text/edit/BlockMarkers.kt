package dev.folio.core.text.edit

import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownBlock

/** The leading syntax of a list item: `  - [ ] `, `3. `. */
internal class ListMarker(
    val indent: Int,
    /** `-`, `*`, `+` or the number with its delimiter (`3.`). */
    val bullet: String,
    val spaces: String,
    /** The task box with its trailing spaces (`[ ] `), or null for a plain item. */
    val box: String?,
    val length: Int,
) {
    val ordered: Boolean get() = bullet[0].isDigit()
    val number: Int get() = bullet.dropLast(1).toInt()

    /** The marker of the item that follows this one: the next number, an unchecked box. */
    fun next(): String = prefix(indent, if (ordered) "${number + 1}${bullet.last()}" else bullet, spaces, box != null)

    companion object {
        /** A marker for [bullet] at [indent] columns. */
        fun prefix(
            indent: Int,
            bullet: String,
            spaces: String = " ",
            task: Boolean = false,
        ): String = " ".repeat(indent) + bullet + spaces.ifEmpty { " " } + if (task) "[ ] " else ""
    }
}

/** Reads and builds the syntax markers that start a block (07-text-engine.md#editing). */
internal object BlockMarkers {
    /** Columns of indentation per list level (matches `BlockParser`). */
    const val INDENT_STEP = 2
    const val MAX_LEVEL = 5

    private val list = Regex("^( *)([-*+]|\\d{1,9}[.)])( +|$)(\\[[ xX]\\]( +|$))?")
    private val quote = Regex("^ {0,3}> ?")
    private val heading = Regex("^ {0,3}#{1,6}( +|$)")
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})")

    fun listMarker(source: String): ListMarker? {
        val match = list.find(source) ?: return null
        val groups = match.groupValues
        return ListMarker(
            groups[INDENT_GROUP].length,
            groups[BULLET_GROUP],
            groups[SPACES_GROUP],
            groups[BOX_GROUP].ifEmpty {
                null
            },
            match.value.length,
        )
    }

    /** Length of the marker (list prefix, `> `, `## `) at the start of [block]'s source; 0 for other blocks. */
    fun length(block: MarkdownBlock): Int =
        when (block.content) {
            is BlockContent.ListItem -> listMarker(block.source)?.length ?: 0
            is BlockContent.Quote -> quote.find(block.source)?.value?.length ?: 0
            is BlockContent.Heading -> heading.find(block.source)?.value?.length ?: 0
            else -> 0
        }

    /** False for the blocks whose text is never merged with another or restyled: code, math, tables and rules. */
    fun isText(block: MarkdownBlock): Boolean =
        when (block.content) {
            is BlockContent.Code, is BlockContent.Math, is BlockContent.Table, BlockContent.Rule -> false
            else -> true
        }

    /** The fence opener (` ``` `, `~~~~`) when [source] starts a fenced code block. */
    fun fenceOpener(source: String): String? = fence.find(source)?.groupValues?.get(1)

    /** True when the last of at least two lines of a fenced block closes it. */
    fun isClosedFence(
        source: String,
        opener: String,
    ): Boolean {
        val lines = source.split('\n')
        if (lines.size < 2) return false
        val closer = lines.last().trim()
        return closer.length >= opener.length && closer.all { it == opener[0] }
    }

    /** True when the `$$` block [source] has its closing `$$`: on its own line or at the end of a longer first line. */
    fun isClosedMath(source: String): Boolean {
        val trimmed = source.trim()
        return trimmed.length >= MIN_CLOSED_MATH && trimmed.endsWith("$$")
    }

    /** Cells of a pipe row (`| a | b |` gives a, b). */
    fun cells(row: String): List<String> =
        row
            .trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split('|')
            .map { it.trim() }

    /** An empty table row with [columns] cells. */
    fun emptyRow(columns: Int): String = "|" + "  |".repeat(columns)

    /** A table of [columns] empty columns with an empty header and one empty row. */
    fun table(columns: Int): String = emptyRow(columns) + "\n|" + " --- |".repeat(columns) + "\n" + emptyRow(columns)

    private const val MIN_CLOSED_MATH = 4

    // Capture groups of the list regex.
    private const val INDENT_GROUP = 1
    private const val BULLET_GROUP = 2
    private const val SPACES_GROUP = 3
    private const val BOX_GROUP = 4
}
