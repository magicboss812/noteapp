package dev.folio.core.text.layout

import kotlin.math.max

/** List marker labels per nesting level (07-text-engine.md#block-styles): bullets disc, circle, dash; numbers 1., a., i. */
internal object ListLabels {
    private val BULLETS = listOf("•", "◦", "–")
    private const val LEVEL_STYLES = 3
    private const val LETTERS = 26
    private const val MAX_ROMAN = 3999
    private val ROMAN =
        listOf(
            1000 to "m",
            900 to "cm",
            500 to "d",
            400 to "cd",
            100 to "c",
            90 to "xc",
            50 to "l",
            40 to "xl",
            10 to "x",
            9 to "ix",
            5 to "v",
            4 to "iv",
            1 to "i",
        )

    /** Bullet glyph for a list [level]. */
    fun bullet(level: Int): String = BULLETS[level % BULLETS.size]

    /** Label of the item numbered [number] at [level]: "3.", "c.", "iii." by level. */
    fun ordered(
        number: Int,
        level: Int,
    ): String =
        when (level % LEVEL_STYLES) {
            0 -> "$number."
            1 -> "${letters(number)}."
            else -> "${roman(number)}."
        }

    private fun letters(number: Int): String {
        var n = max(1, number)
        val sb = StringBuilder()
        while (n > 0) {
            n -= 1
            sb.append('a' + n % LETTERS)
            n /= LETTERS
        }
        return sb.reverse().toString()
    }

    private fun roman(number: Int): String {
        var n = number.coerceIn(1, MAX_ROMAN)
        val sb = StringBuilder()
        for ((value, symbol) in ROMAN) {
            while (n >= value) {
                sb.append(symbol)
                n -= value
            }
        }
        return sb.toString()
    }
}
