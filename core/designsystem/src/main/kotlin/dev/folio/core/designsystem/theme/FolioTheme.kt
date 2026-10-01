package dev.folio.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** All design tokens of one theme instance. */
@Immutable
data class FolioTokens(
    val colors: FolioColors,
    val type: FolioTypography,
    val space: FolioSpacing = FolioSpacing(),
    val shapes: FolioShapes = FolioShapes(),
    val elevation: FolioElevation = FolioElevation(),
    val motion: FolioMotion = FolioMotion(),
)

private val LocalFolioTokens = staticCompositionLocalOf { FolioTokens(FolioColors.Light, FolioTypography.Default) }

/** Token access for composables: `FolioTheme.colors.accent`, `FolioTheme.space.s16`, ... */
object FolioTheme {
    val colors: FolioColors
        @Composable @ReadOnlyComposable
        get() = LocalFolioTokens.current.colors

    val type: FolioTypography
        @Composable @ReadOnlyComposable
        get() = LocalFolioTokens.current.type

    val space: FolioSpacing
        @Composable @ReadOnlyComposable
        get() = LocalFolioTokens.current.space

    val shapes: FolioShapes
        @Composable @ReadOnlyComposable
        get() = LocalFolioTokens.current.shapes

    val elevation: FolioElevation
        @Composable @ReadOnlyComposable
        get() = LocalFolioTokens.current.elevation

    val motion: FolioMotion
        @Composable @ReadOnlyComposable
        get() = LocalFolioTokens.current.motion
}

/**
 * Folio theme. Also sets a matching Material 3 theme so Material building blocks (ripples, text fields, sheets)
 * pick up the tokens; features still read colors and type only through [FolioTheme].
 */
@Composable
fun FolioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens =
        remember(darkTheme, reduceMotion) {
            FolioTokens(
                colors = if (darkTheme) FolioColors.Dark else FolioColors.Light,
                type = FolioTypography.Default,
                motion = FolioMotion(reduceMotion),
            )
        }
    val scheme = remember(tokens.colors) { materialScheme(tokens.colors) }
    val typography = remember(tokens.type) { materialTypography(tokens.type) }
    CompositionLocalProvider(LocalFolioTokens provides tokens) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides tokens.colors.textPrimary, content = content)
        }
    }
}

private fun materialScheme(c: FolioColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accentFill,
        onPrimary = c.onAccent,
        primaryContainer = c.accentContainerStrong,
        onPrimaryContainer = c.onAccentContainerStrong,
        secondary = c.accent,
        onSecondary = c.onAccent,
        secondaryContainer = c.accentContainer,
        onSecondaryContainer = c.accent,
        background = c.page,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceGroup,
        onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surfaceGroup,
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.surface,
        outline = c.border,
        outlineVariant = c.divider,
        error = c.danger,
        scrim = c.scrim,
    )
}

private fun materialTypography(t: FolioTypography) =
    Typography(
        displayLarge = t.display,
        displaySmall = t.display,
        headlineSmall = t.headline,
        titleLarge = t.title,
        titleMedium = t.titleSmall,
        bodyLarge = t.body,
        bodyMedium = t.label,
        bodySmall = t.caption,
        labelLarge = t.bodyMedium,
        labelMedium = t.labelSmall,
        labelSmall = t.caption,
    )
