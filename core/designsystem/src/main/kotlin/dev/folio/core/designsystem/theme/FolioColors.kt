// Values are the color table of 11-design-system.md#colors; naming each literal would hide the table.
@file:Suppress("MagicNumber")

package dev.folio.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Folder card tints (11-design-system.md#colors). */
enum class FolderTint { Yellow, Blue, Green, Pink, Purple, Gray }

/** UI color tokens. Content colors (paper, templates, ink, PDFs) never come from here (R-PAGE-03). */
@Immutable
data class FolioColors(
    val isDark: Boolean,
    val background: Color,
    /** Radial glow painted from the top-right corner over [background]. */
    val backgroundGlow: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    /** Text and icons on an [accent] fill. */
    val onAccent: Color,
    val accentSoft: Color,
    /** Accent on inverted surfaces (snackbar action). */
    val accentInverse: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val canvasSurround: Color,
    /** Page outline on the canvas; the same in both modes because paper never changes. */
    val pageBorder: Color,
    /** Dim layer behind sheets and dialogs. */
    val scrim: Color,
    val shadow: Color,
    private val folderTints: List<Color>,
) {
    /** Background of a folder card with [tint]. */
    fun folderTint(tint: FolderTint): Color = folderTints[tint.ordinal]

    companion object {
        private val tintHues =
            listOf(Color(0xFFFCE7A0), Color(0xFFDBEAFE), Color(0xFFDCFCE7), Color(0xFFFCE7F3), Color(0xFFEDE9FE), Color(0xFFF1F3F6))
        private const val DARK_TINT_ALPHA = 0.22f

        /** Light theme tokens. */
        val Light =
            FolioColors(
                isDark = false,
                background = Color(0xFFF7F8FA),
                backgroundGlow = Color(0xFF2563EB).copy(alpha = 0.06f),
                surface = Color(0xFFFFFFFF),
                surfaceMuted = Color(0xFFF1F3F6),
                border = Color(0xFFE5E7EB),
                textPrimary = Color(0xFF111827),
                textSecondary = Color(0xFF6B7280),
                textTertiary = Color(0xFF9CA3AF),
                accent = Color(0xFF2563EB),
                onAccent = Color(0xFFFFFFFF),
                accentSoft = Color(0xFFE8F0FE),
                accentInverse = Color(0xFF5B8CFF),
                danger = Color(0xFFDC2626),
                success = Color(0xFF16A34A),
                warning = Color(0xFFD97706),
                canvasSurround = Color(0xFFE9ECF1),
                pageBorder = Color(0xFFE3E6EB),
                scrim = Color.Black.copy(alpha = 0.32f),
                shadow = Color.Black,
                folderTints = tintHues,
            )

        /** Dark theme tokens; folder tints are their hues at 22% over the dark surface. */
        val Dark =
            FolioColors(
                isDark = true,
                background = Color(0xFF0F1115),
                backgroundGlow = Color(0xFF5B8CFF).copy(alpha = 0.08f),
                surface = Color(0xFF171A21),
                surfaceMuted = Color(0xFF1F232C),
                border = Color(0xFF2A2F3A),
                textPrimary = Color(0xFFF3F4F6),
                textSecondary = Color(0xFFA1A7B3),
                textTertiary = Color(0xFF6B7280),
                accent = Color(0xFF5B8CFF),
                onAccent = Color(0xFFFFFFFF),
                accentSoft = Color(0xFF1E2A4A),
                accentInverse = Color(0xFF2563EB),
                danger = Color(0xFFF87171),
                success = Color(0xFF4ADE80),
                warning = Color(0xFFFBBF24),
                canvasSurround = Color(0xFF0B0D11),
                pageBorder = Color(0xFFE3E6EB),
                scrim = Color.Black.copy(alpha = 0.48f),
                shadow = Color.Black,
                folderTints = tintHues.map { it.copy(alpha = DARK_TINT_ALPHA).compositeOver(Color(0xFF171A21)) },
            )
    }
}
