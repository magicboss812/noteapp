// Durations and curves are the motion table of 11-design-system.md#motion.
@file:Suppress("MagicNumber")

package dev.folio.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/** Motion tokens. With [reduceMotion] every duration is halved and container transforms become crossfades. */
@Immutable
data class FolioMotion(
    val reduceMotion: Boolean = false,
) {
    val fastMs: Int get() = scaled(FAST_MS)
    val standardMs: Int get() = scaled(STANDARD_MS)
    val emphasizedMs: Int get() = scaled(EMPHASIZED_MS)

    /** Press states, icon swaps, chip selection. */
    fun <T> fast(): FiniteAnimationSpec<T> = tween(fastMs, easing = StandardEasing)

    /** Options row changes, menus, tab indicator. */
    fun <T> standard(): FiniteAnimationSpec<T> = tween(standardMs, easing = StandardEasing)

    /** Sheets, card to editor, split view open. */
    fun <T> emphasized(): FiniteAnimationSpec<T> = tween(emphasizedMs, easing = EmphasizedDecelerateEasing)

    /** Toolbar dock snapping, floating toolbar release, selection box. */
    fun <T> spring(): SpringSpec<T> = spring(dampingRatio = SPRING_DAMPING, stiffness = SPRING_STIFFNESS)

    private fun scaled(durationMs: Int) = if (reduceMotion) durationMs / 2 else durationMs

    companion object {
        const val FAST_MS = 120
        const val STANDARD_MS = 200
        const val EMPHASIZED_MS = 320
        const val SPRING_DAMPING = 0.85f
        const val SPRING_STIFFNESS = 500f

        /** CubicBezier(0.2, 0, 0, 1). */
        val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** CubicBezier(0.05, 0.7, 0.1, 1). */
        val EmphasizedDecelerateEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    }
}
