package dev.folio.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

// Unit tests run with the module as working directory.
private val DESIGN_MD = File("../../docs/design/notewise/DESIGN.md")
private val HEX = Regex("#([0-9A-Fa-f]{6})")

/** DESIGN.md color tokens used by Folio and the FolioColors field that carries each one. */
private val TOKEN_FIELDS: Map<String, (FolioColors) -> Color> =
    mapOf(
        "bg.page" to { it.page },
        "bg.sidebar" to { it.sidebar },
        "bg.canvas" to { it.canvas },
        "bg.overlay" to { it.overlay },
        "surface" to { it.surface },
        "surface.dialog" to { it.surfaceDialog },
        "surface.header" to { it.surfaceHeader },
        "surface.toolbar" to { it.surfaceToolbar },
        "surface.bar" to { it.surfaceBar },
        "surface.inset" to { it.surfaceInset },
        "surface.group" to { it.surfaceGroup },
        "surface.tinted" to { it.surfaceTinted },
        "surface.settingsCard" to { it.settingsCard },
        "surface.multiBar" to { it.multiBar },
        "border" to { it.border },
        "divider" to { it.divider },
        "text.primary" to { it.textPrimary },
        "text.secondary" to { it.textSecondary },
        "text.tertiary" to { it.textTertiary },
        "text.disabled" to { it.textDisabled },
        "icon" to { it.icon },
        "accent" to { it.accent },
        "accent.fill" to { it.accentFill },
        "onAccent" to { it.onAccent },
        "accent.container" to { it.accentContainer },
        "accent.containerStrong" to { it.accentContainerStrong },
        "accent.tile" to { it.accentTile },
        "accent.track" to { it.accentTrack },
        "nav.selected" to { it.navSelected },
        "closeChip" to { it.closeChip },
        "danger" to { it.danger },
        "canvas.selection" to { it.canvasSelection },
        "search.highlight" to { it.searchHighlight },
    )

/** Rows checked by dedicated tests below, or not UI tokens (paper is document content, R-PAGE-03). */
private val SPECIAL_ROWS = setOf("bg.library", "scrim", "paper")

private data class TokenRow(
    val token: String,
    val dark: String,
    val light: String,
)

class TokensTest {
    private val design = DESIGN_MD.readText()

    private val rows: List<TokenRow> =
        design
            .substringAfter("## 2. Color tokens")
            .substringBefore("Palettes.")
            .lines()
            .filter { it.startsWith("| ") && !it.startsWith("| Token") }
            .map { line ->
                val cells = line.trim('|').split('|').map(String::trim)
                TokenRow(cells[0], cells[1], cells[2])
            }

    @Test
    fun colorTable_everyDesignToken_isMappedOrSpecial() {
        val unknown = rows.map { it.token }.filterNot { it in TOKEN_FIELDS || it in SPECIAL_ROWS }

        assertThat(rows).isNotEmpty()
        assertWithMessage("DESIGN.md tokens without a FolioColors field").that(unknown).isEmpty()
    }

    @Test
    fun colorTable_mappedTokens_equalTheirFirstMeasuredValue() {
        rows.filter { it.token in TOKEN_FIELDS }.forEach { row ->
            val field = TOKEN_FIELDS.getValue(row.token)
            firstHex(row.dark)?.let { assertWithMessage("${row.token} dark").that(field(FolioColors.Dark)).isEqualTo(it) }
            // "(~)" marks a light value DESIGN.md leaves open; Folio picks it (11-design-system.md#colors).
            if (!row.light.startsWith("(~)")) {
                firstHex(row.light)?.let { assertWithMessage("${row.token} light").that(field(FolioColors.Light)).isEqualTo(it) }
            }
        }
    }

    @Test
    fun libraryBackground_gradientStops_matchDesign() {
        val row = rows.single { it.token == "bg.library" }
        val dark = HEX.findAll(row.dark).map { color(it.groupValues[1]) }.toList()
        val light = HEX.findAll(row.light).map { color(it.groupValues[1]) }.toList()

        assertThat(dark).containsAtLeast(FolioColors.Dark.libraryTop, FolioColors.Dark.libraryMid, FolioColors.Dark.libraryBottom).inOrder()
        // Light: white #FEFEFF with a top band #88AFE6 -> #CDE3EE.
        assertThat(
            light,
        ).containsAtLeast(FolioColors.Light.libraryBottom, FolioColors.Light.libraryTop, FolioColors.Light.libraryMid).inOrder()
    }

    @Test
    fun scrim_bothModes_isBlackWithinDesignRange() {
        listOf(FolioColors.Dark, FolioColors.Light).forEach {
            assertThat(it.scrim.copy(alpha = 1f)).isEqualTo(Color.Black)
            assertThat(it.scrim.alpha).isWithin(0.06f).of(0.39f)
        }
    }

    @Test
    fun folderColors_matchDesignListInOrder() {
        val list = design.substringAfter("Folder colors (dark):").substringBefore(";")
        val expected = HEX.findAll(list).map { color(it.groupValues[1]) }.toList()

        assertThat(FolderColor.entries.map { it.color }).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun folderColors_onColor_isDarkOnLightFolders() {
        assertThat(FolderColor.Yellow.onColor).isEqualTo(Color(0xFF2E2D2B))
        assertThat(FolderColor.Wine.onColor).isEqualTo(Color.White)
    }

    @Test
    fun sizes_matchDesignComponents() {
        val space = FolioSpacing()

        assertThat(listOf(space.toolbarPill, space.toolbarCell, space.touchTarget)).containsExactly(44.dp, 36.dp, 44.dp).inOrder()
        assertThat(listOf(space.iconToolbar, space.iconMenu, space.iconSmall)).containsExactly(24.dp, 20.dp, 16.dp).inOrder()
        assertThat(listOf(space.colorDot, space.indicatorDot)).containsExactly(22.dp, 4.dp).inOrder()
        assertThat(listOf(space.dividerWidth, space.dividerLength)).containsExactly(2.dp, 26.dp).inOrder()
        assertThat(listOf(space.popoverWidth, space.dialogWidth, space.closeChip)).containsExactly(340.dp, 560.dp, 24.dp).inOrder()
        assertThat(space.chromeInset).isEqualTo(8.dp)
    }

    @Test
    fun pageBorder_bothModes_isTheSame() {
        // Dark mode never changes paper (R-PAGE-03).
        assertThat(FolioColors.Dark.pageBorder).isEqualTo(FolioColors.Light.pageBorder)
    }

    @Test
    fun motion_reduceMotion_halvesDurations() {
        val normal = FolioMotion()
        val reduced = FolioMotion(reduceMotion = true)

        assertThat(listOf(normal.fastMs, normal.standardMs, normal.emphasizedMs)).containsExactly(120, 200, 320).inOrder()
        assertThat(listOf(reduced.fastMs, reduced.standardMs, reduced.emphasizedMs)).containsExactly(60, 100, 160).inOrder()
    }

    @Test
    fun motion_longestDuration_staysWithinRuleLimit() {
        // .claude/rules/compose-ui.md: nothing longer than 350 ms.
        assertThat(FolioMotion().emphasizedMs).isAtMost(350)
    }

    private fun firstHex(cell: String): Color? = HEX.find(cell)?.let { color(it.groupValues[1]) }

    private fun color(hex: String): Color = Color(0xFF000000 or hex.toLong(16))
}
