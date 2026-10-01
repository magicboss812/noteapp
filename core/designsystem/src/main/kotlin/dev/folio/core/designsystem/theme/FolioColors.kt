// Values are the color table of 11-design-system.md#colors; naming each literal would hide the table.
@file:Suppress("MagicNumber")

package dev.folio.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Folder card colors (11-design-system.md#colors, DESIGN folder colors in order). */
enum class FolderColor(
    val argb: Long,
) {
    Green(0xFF74C662),
    Teal(0xFF4088A6),
    Wine(0xFF7C1F45),
    Mauve(0xFFAB7C86),
    Orange(0xFFDB6945),
    Yellow(0xFFEABE4D),
    Purple(0xFF430D73),
    Sky(0xFF4CA2C8),
    Pink(0xFFEB6CA6),
    Rust(0xFFBA5522),
    Berry(0xFF96244F),
    Forest(0xFF479A5D),
    ;

    /** Fill of the folder card. */
    val color: Color get() = Color(argb)

    /** Title color on [color]: dark on light folders, white on dark ones. */
    val onColor: Color get() = if (color.luminance() > ON_COLOR_LUMINANCE) Color(0xFF2E2D2B) else Color.White

    private companion object {
        const val ON_COLOR_LUMINANCE = 0.4f
    }
}

/** UI color tokens (DESIGN token names in KDoc). Content colors (paper, templates, ink, PDFs) never come from here (R-PAGE-03). */
@Immutable
data class FolioColors(
    val isDark: Boolean,
    /** bg.library gradient: top, middle, bottom. */
    val libraryTop: Color,
    val libraryMid: Color,
    val libraryBottom: Color,
    /** bg.library dot grid. */
    val libraryDots: Color,
    /** bg.page: settings page, folder view, plain screens. */
    val page: Color,
    /** bg.sidebar. */
    val sidebar: Color,
    /** bg.canvas: editor backdrop around pages. */
    val canvas: Color,
    /** bg.overlay: full-screen search. */
    val overlay: Color,
    /** Menus, popovers, tool panels, dialogs. */
    val surface: Color,
    val surfaceDialog: Color,
    val surfaceHeader: Color,
    /** Editor toolbar pills. */
    val surfaceToolbar: Color,
    /** On-canvas selection and text context bars. */
    val surfaceBar: Color,
    val surfaceInset: Color,
    val surfaceGroup: Color,
    val surfaceTinted: Color,
    val settingsCard: Color,
    val multiBar: Color,
    val border: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    /** Icons in top pills, library and sidebar. */
    val icon: Color,
    /** Icons in the toolbar rows. */
    val iconToolbar: Color,
    val accent: Color,
    /** FAB and filled buttons. */
    val accentFill: Color,
    /** Text and icons on an [accentFill]. */
    val onAccent: Color,
    /** Selected filter chip. */
    val accentContainer: Color,
    /** Selected tool circle, selected segment. */
    val accentContainerStrong: Color,
    val onAccentContainerStrong: Color,
    /** Selected kind tiles and action tiles. */
    val accentTile: Color,
    /** Inactive slider track. */
    val accentTrack: Color,
    val navSelected: Color,
    val closeChip: Color,
    val closeChipGlyph: Color,
    /** Destructive labels and icons (never a fill). */
    val danger: Color,
    val success: Color,
    val warning: Color,
    /** Lasso box and text box outline on the canvas. */
    val canvasSelection: Color,
    val searchHighlight: Color,
    /** Page outline on the canvas; the same in both modes because paper never changes. */
    val pageBorder: Color,
    /** Dim layer behind modal dialogs and sheets. */
    val scrim: Color,
    val shadow: Color,
) {
    companion object {
        private const val SCRIM_ALPHA = 0.40f
        private const val DARK_DOTS_ALPHA = 0.06f

        /** Light theme tokens. */
        val Light =
            FolioColors(
                isDark = false,
                libraryTop = Color(0xFF88AFE6),
                libraryMid = Color(0xFFCDE3EE),
                libraryBottom = Color(0xFFFEFEFF),
                libraryDots = Color.White,
                page = Color(0xFFFAFAFB),
                sidebar = Color(0xFFF3F3F3),
                canvas = Color(0xFFEEEEEF),
                overlay = Color(0xFFFAFAFB),
                surface = Color(0xFFFAF9FA),
                surfaceDialog = Color(0xFFFAF9FA),
                surfaceHeader = Color(0xFFFEFDFE),
                surfaceToolbar = Color(0xFFF9F9FA),
                surfaceBar = Color(0xFFF9F9FA),
                surfaceInset = Color(0xFFFCFCFD),
                surfaceGroup = Color(0xFFF1F2F4),
                surfaceTinted = Color(0xFFEDF0F7),
                settingsCard = Color(0xFFEDF0F7),
                multiBar = Color(0xFFF1F2F4),
                border = Color(0xFFEBEBEC),
                divider = Color(0xFFE6E6E6),
                textPrimary = Color(0xFF1B1B1F),
                textSecondary = Color(0xFF4C4D55),
                textTertiary = Color(0xFF8A8B92),
                textDisabled = Color(0xFFB4B5BA),
                icon = Color(0xFF1F1F23),
                iconToolbar = Color(0xFF343438),
                accent = Color(0xFF4B85E0),
                accentFill = Color(0xFF4B85E0),
                onAccent = Color(0xFFFFFFFF),
                accentContainer = Color(0xFFE6EEF5),
                accentContainerStrong = Color(0xFFC6DBF7),
                onAccentContainerStrong = Color(0xFF12263B),
                accentTile = Color(0xFFE6EEF5),
                accentTrack = Color(0xFFC6DBF7),
                navSelected = Color(0xFFDEDEE2),
                closeChip = Color(0xFFE8E8EA),
                closeChipGlyph = Color(0xFF6E6F78),
                danger = Color(0xFFD4504A),
                success = Color(0xFF16A34A),
                warning = Color(0xFFD97706),
                canvasSelection = Color(0xFF4997F3),
                searchHighlight = Color(0xFFF3F350),
                pageBorder = Color(0xFFE3E6EB),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
                shadow = Color.Black,
            )

        /** Dark theme tokens. */
        val Dark =
            FolioColors(
                isDark = true,
                libraryTop = Color(0xFF213044),
                libraryMid = Color(0xFF141519),
                libraryBottom = Color(0xFF0A0A0B),
                libraryDots = Color.White.copy(alpha = DARK_DOTS_ALPHA),
                page = Color(0xFF070708),
                sidebar = Color(0xFF1A1A1B),
                canvas = Color(0xFF252526),
                overlay = Color(0xFF1C1E21),
                surface = Color(0xFF222428),
                surfaceDialog = Color(0xFF232529),
                surfaceHeader = Color(0xFF1E2023),
                surfaceToolbar = Color(0xFF292C31),
                surfaceBar = Color(0xFF1C1C1C),
                surfaceInset = Color(0xFF1D1F22),
                surfaceGroup = Color(0xFF2B2E34),
                surfaceTinted = Color(0xFF333643),
                settingsCard = Color(0xFF34394A),
                multiBar = Color(0xFF2F363F),
                border = Color(0xFF4D4F57),
                divider = Color(0xFF494B52),
                textPrimary = Color(0xFFE1E1E8),
                textSecondary = Color(0xFFC6C8D1),
                textTertiary = Color(0xFF8D8D93),
                textDisabled = Color(0xFF67686D),
                icon = Color(0xFFD7D7DD),
                iconToolbar = Color(0xFFC1C3CC),
                accent = Color(0xFF6B99F0),
                accentFill = Color(0xFF6997EE),
                onAccent = Color(0xFF102B6E),
                accentContainer = Color(0xFF28395A),
                accentContainerStrong = Color(0xFF23496E),
                onAccentContainerStrong = Color(0xFFBDD1EA),
                accentTile = Color(0xFF2B3C5D),
                accentTrack = Color(0xFF22486D),
                navSelected = Color(0xFF2C2C2E),
                closeChip = Color(0xFF2C2C2E),
                closeChipGlyph = Color(0xFF8E8F99),
                danger = Color(0xFFF3B8B1),
                success = Color(0xFF4ADE80),
                warning = Color(0xFFFBBF24),
                canvasSelection = Color(0xFF4997F3),
                searchHighlight = Color(0xFFF3F350),
                pageBorder = Color(0xFFE3E6EB),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
                shadow = Color.Black,
            )
    }
}
