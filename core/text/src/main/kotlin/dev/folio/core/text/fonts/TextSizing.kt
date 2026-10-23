package dev.folio.core.text.fonts

import kotlin.math.ceil
import kotlin.math.max

/** Body size presets a flow can pick; [capFraction] is the body cap height as a fraction of U. */
enum class BodySize(
    val capFraction: Float,
) {
    S(0.38f),
    M(0.45f),
    L(0.52f),
}

/**
 * Text roles with their cap height at body size M ([capFractionAtM], fraction of U), their minimum
 * line box ([minBoxUnits] x U) and the style they use (07-text-engine.md#font-normalization).
 * Table cells and sticky notes use [BODY].
 */
enum class TextRole(
    val capFractionAtM: Float,
    val minBoxUnits: Int,
    val bold: Boolean = false,
    val mono: Boolean = false,
) {
    BODY(0.45f, 1),
    H1(0.95f, 2, bold = true),
    H2(0.75f, 2, bold = true),
    H3(0.55f, 1, bold = true),
    CODE(0.40f, 1, mono = true),
}

/** A derived size: font size in pt, the cap height it produces (fraction of U) and the line box height in U. */
data class DerivedSize(
    val fontSizePt: Float,
    val capFraction: Float,
    val boxUnits: Int,
)

/**
 * Size derivation (07-text-engine.md#font-normalization): the font size is chosen so the cap height
 * equals a fraction of U, never given in sp. A flow's body cap fraction is a [BodySize] preset or a
 * free value in [MIN_BODY_CAP]..[MAX_BODY_CAP] (A-039); every role scales with it and the line box
 * grows in whole U steps so baselines stay on rules.
 */
object TextSizing {
    /** Smallest free body cap fraction. */
    const val MIN_BODY_CAP = 0.30f

    /** Largest free body cap fraction (a 3U body line box). */
    const val MAX_BODY_CAP = 1.80f

    /** Largest cap height (fraction of U) that one grid unit of line box holds; above it the box grows. */
    const val MAX_CAP_PER_UNIT = 0.66f

    /** Absorbs float noise so a cap of exactly n x [MAX_CAP_PER_UNIT] stays in n units. */
    private const val EPSILON = 1e-4f

    /** [bodyCap] clamped to the free size range. */
    fun clampBodyCap(bodyCap: Float): Float = if (bodyCap.isNaN()) BodySize.M.capFraction else bodyCap.coerceIn(MIN_BODY_CAP, MAX_BODY_CAP)

    /** Cap height of [role] as a fraction of U in a flow whose body cap fraction is [bodyCap]. */
    fun capFraction(
        role: TextRole,
        bodyCap: Float,
    ): Float = role.capFractionAtM * clampBodyCap(bodyCap) / BodySize.M.capFraction

    /** Line box height in U for [role] with cap fraction [capFraction]. */
    fun boxUnits(
        role: TextRole,
        capFraction: Float,
    ): Int = max(role.minBoxUnits, ceil(capFraction / MAX_CAP_PER_UNIT - EPSILON).toInt())

    /** Font size in pt whose cap height is [capFraction] x [unitPt] for a typeface with [capRatio]. */
    fun fontSizePt(
        capRatio: Float,
        capFraction: Float,
        unitPt: Float,
    ): Float = capFraction * unitPt / capRatio

    /** Full derivation for [role] in [family] at grid unit [unitPt] and flow body cap [bodyCap]. */
    fun derive(
        metrics: FontMetricsCache,
        family: TextFontFamily,
        role: TextRole,
        bodyCap: Float,
        unitPt: Float,
    ): DerivedSize {
        val cap = capFraction(role, bodyCap)
        val proportions = metrics.proportions(family, role.bold)
        return DerivedSize(fontSizePt(proportions.capRatio, cap, unitPt), cap, boxUnits(role, cap))
    }
}
