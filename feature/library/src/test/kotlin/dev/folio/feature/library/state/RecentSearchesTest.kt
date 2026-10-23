package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecentSearchesTest {
    @Test
    fun push_newestFirstNoDuplicatesCapped() {
        var recent = emptyList<String>()
        (1..12).forEach { recent = RecentSearches.push(recent, "query $it") }
        recent = RecentSearches.push(recent, "  QUERY   8 ")

        assertThat(recent).hasSize(RecentSearches.MAX)
        assertThat(recent.first()).isEqualTo("QUERY 8")
        assertThat(recent.count { it.equals("query 8", ignoreCase = true) }).isEqualTo(1)
        assertThat(recent.last()).isEqualTo("query 3")
    }

    @Test
    fun push_blankQuery_changesNothing() {
        val recent = listOf("kinematics")

        assertThat(RecentSearches.push(recent, "   ")).isEqualTo(recent)
    }

    @Test
    fun encodeDecode_roundTripsAndToleratesGarbage() {
        val recent = listOf("über", "a \"quoted\" word")

        assertThat(RecentSearches.decode(RecentSearches.encode(recent))).isEqualTo(recent)
        assertThat(RecentSearches.decode(null)).isEmpty()
        assertThat(RecentSearches.decode("{not a list")).isEmpty()
    }

    @Test
    fun isSearchable_needsLetterOrDigit() {
        assertThat(isSearchable("  -- ")).isFalse()
        assertThat(isSearchable(" é ")).isTrue()
        assertThat(isSearchable("7")).isTrue()
    }
}
