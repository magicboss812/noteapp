package dev.folio.app.debug

import kotlin.random.Random

/** Parsed `seed-text n[,page]`: about [words] words of Markdown in the body flow of stack page [page] (1-based). */
internal data class SeedText(
    val words: Int,
    val page: Int,
) {
    companion object {
        const val MAX_WORDS = 50_000

        fun parse(arg: String?): SeedText? {
            val parts = arg?.split(',')?.takeIf { it.size in 1..2 } ?: return null
            val words = parts[0].toIntOrNull()?.takeIf { it in 1..MAX_WORDS } ?: return null
            val page = if (parts.size == 2) parts[1].toIntOrNull()?.takeIf { it >= 1 } else 1
            return page?.let { SeedText(words, it) }
        }
    }
}

/**
 * Note-like Markdown for text perf runs (12-performance.md#measurement, P06-T11): sections of a `##` heading, two
 * paragraphs with some bold and italic words, and every third section a bullet list and a task item. Deterministic.
 */
internal object SyntheticText {
    private const val SEED = 20261023L
    private const val PARAGRAPH_WORDS = 60
    private const val LIST_ITEMS = 3
    private const val ITEM_WORDS = 6
    private const val LIST_EVERY = 3
    private const val EMPHASIS_EVERY = 17

    private val vocabulary =
        (
            "the grid line text block page flow frame note ink pen layout caret baseline margin heading list " +
                "paragraph quote table math font size zoom tile render cache index search folder tag export " +
                "import sketch idea plan review draft summary lecture chapter section example proof result"
        ).split(' ')

    fun generate(words: Int): String {
        val random = Random(SEED)
        val out = StringBuilder()
        var left = words
        var section = 0

        fun phrase(count: Int): String =
            (0 until count).joinToString(" ") { i ->
                val word = vocabulary[random.nextInt(vocabulary.size)]
                when {
                    i % EMPHASIS_EVERY == 5 -> "**$word**"
                    i % EMPHASIS_EVERY == 11 -> "*$word*"
                    else -> word
                }
            }
        while (left > 0) {
            section++
            if (out.isNotEmpty()) out.append("\n\n")
            out.append("## Section ").append(section)
            repeat(2) {
                if (left <= 0) return@repeat
                val n = minOf(PARAGRAPH_WORDS, left)
                out.append("\n\n").append(phrase(n).replaceFirstChar { it.uppercase() }).append('.')
                left -= n
            }
            if (section % LIST_EVERY == 0 && left > 0) {
                out.append('\n')
                repeat(LIST_ITEMS) { out.append("\n- ").append(phrase(ITEM_WORDS)) }
                out.append("\n\n- [ ] ").append(phrase(ITEM_WORDS))
                left -= (LIST_ITEMS + 1) * ITEM_WORDS
            }
        }
        return out.toString()
    }
}
