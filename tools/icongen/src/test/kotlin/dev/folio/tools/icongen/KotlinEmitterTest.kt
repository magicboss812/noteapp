package dev.folio.tools.icongen

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KotlinEmitterTest {
    @Test
    fun propertyName_kebabCaseWithDigits_isPascalCase() {
        assertThat(KotlinEmitter.propertyName("arrow-down-wide-narrow")).isEqualTo("ArrowDownWideNarrow")
        assertThat(KotlinEmitter.propertyName("columns-2")).isEqualTo("Columns2")
        assertThat(KotlinEmitter.propertyName("x")).isEqualTo("X")
    }

    @Test
    fun chunk_longPath_splitsAtBoundariesAndRejoinsLosslessly() {
        val path = "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915".repeat(5)

        val chunks = KotlinEmitter.chunk(path)

        assertThat(chunks.size).isGreaterThan(1)
        chunks.forEach { assertThat(it.length).isAtMost(KotlinEmitter.CHUNK_CHARS) }
        assertThat(chunks.joinToString(" ").replace(" ", "")).isEqualTo(path.replace(" ", ""))
        chunks.forEach { assertThat(it).doesNotContainMatch("^\\s|\\s$") }
    }

    @Test
    fun emit_iconsSortedByName_withFilledAndChunkedPaths() {
        val icons =
            listOf(
                LucideIcon("tag", listOf(IconPath("M1 1h2", filled = false), IconPath("M7 7.5Z", filled = true))),
                LucideIcon("check", listOf(IconPath("M20 6 9 17l-5-5", filled = false))),
            )

        val source = KotlinEmitter.emit(icons, "1.49.0")

        assertThat(source).contains("from Lucide 1.49.0")
        assertThat(source.indexOf("val Check:")).isLessThan(source.indexOf("val Tag:"))
        assertThat(source).contains(
            """
            |    val Tag: ImageVector by lazy {
            |        lucideIcon(
            |            "Tag",
            |            stroke("M1 1h2"),
            |            filled("M7 7.5Z"),
            |        )
            |    }
            """.trimMargin(),
        )
        source.lines().forEach { assertThat(it.length).isAtMost(140) }
    }
}
