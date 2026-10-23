package dev.folio.core.text.edit

import dev.folio.core.model.edit.TextEdit

/** The flow edit behind a structural change: the one replacement that turns the old text into the new. */
object FlowTextDiff {
    /** The edit that turns [before] into [after], or null when they are equal. Never cuts a surrogate pair in two. */
    fun between(
        before: String,
        after: String,
    ): TextEdit? {
        if (before == after) return null
        val shorter = minOf(before.length, after.length)
        var prefix = 0
        while (prefix < shorter && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < shorter - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
        if (prefix > 0 && before[prefix - 1].isHighSurrogate()) prefix--
        if (suffix > 0 && before[before.length - suffix].isLowSurrogate()) suffix--
        return TextEdit(prefix, before.length - suffix, after.substring(prefix, after.length - suffix))
    }
}
