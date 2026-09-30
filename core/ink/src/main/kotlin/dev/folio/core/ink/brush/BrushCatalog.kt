package dev.folio.core.ink.brush

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps [BrushSpec]s to androidx.ink brushes (06-ink-input.md#brushes). The only place that turns a
 * spec into a [BrushFamily]: families are built once per (kind, version, pressure curve, tilt) and
 * cached, and every spec renders with the definitions of its own version. Thread-safe.
 */
class BrushCatalog internal constructor(
    definitions: List<BrushDefinitions>,
) {
    private val byVersion: Map<Int, BrushDefinitions> = definitions.associateBy { it.version }

    /** Newest catalog version: new strokes are drawn with it. */
    val latestVersion: Int = byVersion.keys.max()

    private val oldestVersion: Int = byVersion.keys.min()
    private val families = ConcurrentHashMap<FamilyKey, BrushFamily>()
    private val keys = ConcurrentHashMap<BrushFamily, FamilyKey>()

    init {
        require(byVersion.size == definitions.size) { "duplicate brush versions" }
        require((oldestVersion..latestVersion).all { it in byVersion }) { "brush versions have gaps" }
    }

    /**
     * Brush for [spec]; [tilt] adds tilt behaviors to kinds that have them (pass true only when the
     * stylus reports tilt, or when stored inputs carry tilt). Width is limited to the custom range.
     */
    fun brush(
        spec: BrushSpec,
        tilt: Boolean = false,
    ): Brush = Brush.createWithColorIntArgb(family(spec, tilt), spec.argb, BrushPresets.clampWidth(spec.sizePt), EPSILON_PT)

    /** Cached family for [spec] (color and width do not matter). */
    fun family(
        spec: BrushSpec,
        tilt: Boolean = false,
    ): BrushFamily {
        val key = keyOf(spec, tilt)
        return families.getOrPut(key) {
            definitions(key.version).family(key.kind, key.gamma, key.tilt).also { keys.putIfAbsent(it, key) }
        }
    }

    /**
     * Spec a catalog [brush] was made from, for turning finished wet strokes into stored strokes;
     * null for brushes this catalog did not build. The pressure curve comes back normalized.
     */
    fun specOf(brush: Brush): BrushSpec? {
        val key = keys[brush.family] ?: return null
        return BrushSpec(key.kind, brush.colorIntArgb, brush.size, key.version, key.gamma)
    }

    /** Version whose definitions render [version]: itself, or the nearest known one (damaged or newer files). */
    fun resolveVersion(version: Int): Int = version.coerceIn(oldestVersion, latestVersion)

    private fun definitions(version: Int): BrushDefinitions = byVersion.getValue(version)

    private fun keyOf(
        spec: BrushSpec,
        tilt: Boolean,
    ): FamilyKey {
        val version = resolveVersion(spec.version)
        val defs = definitions(version)
        val gamma = if (defs.usesPressure(spec.kind)) PressureCurve.normalize(spec.pressureGamma) else 1f
        return FamilyKey(spec.kind, version, gamma, tilt && defs.usesTilt(spec.kind))
    }

    private data class FamilyKey(
        val kind: BrushKind,
        val version: Int,
        val gamma: Float,
        val tilt: Boolean,
    )

    companion object {
        /** Brush epsilon in pt: the smallest distance the mesher distinguishes (page space). */
        const val EPSILON_PT = 0.05f

        /** The app's catalog with every shipped version. */
        val DEFAULT: BrushCatalog = BrushCatalog(listOf(BrushDefinitionsV1))
    }
}
