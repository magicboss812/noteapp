package dev.folio.core.ink.brush

import androidx.ink.brush.BrushFamily
import dev.folio.core.model.BrushKind

/**
 * One frozen version of every brush family. Never edit a shipped version: a visual change is a new
 * implementation with the next [version], registered in [BrushCatalog.DEFAULT]; old strokes keep
 * rendering with the version stored in their `BrushSpec`.
 */
internal interface BrushDefinitions {
    /** Catalog version these definitions implement (`BrushSpec.version`). */
    val version: Int

    /** True when [kind] reacts to pressure (so the pressure curve matters). */
    fun usesPressure(kind: BrushKind): Boolean

    /** True when [kind] has tilt behaviors. */
    fun usesTilt(kind: BrushKind): Boolean

    /**
     * Builds the family for [kind] with the normalized pressure curve [gamma]; [tilt] adds tilt
     * behaviors (only requested when [usesTilt] and the stylus reports tilt).
     */
    fun family(
        kind: BrushKind,
        gamma: Float,
        tilt: Boolean,
    ): BrushFamily
}
