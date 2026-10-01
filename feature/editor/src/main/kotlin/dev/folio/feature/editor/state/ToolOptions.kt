package dev.folio.feature.editor.state

import androidx.compose.runtime.Immutable
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.ink.brush.PressureCurve
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/** Brush kinds of the pen tool, in options-row order (the highlighter is its own tool). */
val PEN_KINDS: ImmutableList<BrushKind> =
    persistentListOf(BrushKind.BALLPOINT, BrushKind.FOUNTAIN, BrushKind.PENCIL, BrushKind.MARKER)

/** A tool's color dots and the selected one (06-ink-input.md#colors: up to [MAX] favorites per tool). */
@Immutable
data class Swatches(
    val colors: ImmutableList<Int>,
    val selected: Int = 0,
) {
    init {
        require(colors.size in 1..MAX) { "1..$MAX colors" }
        require(selected in colors.indices) { "selected out of range" }
    }

    /** The selected color (ARGB). */
    val argb: Int get() = colors[selected]

    /** Whether [add] appends a dot (false: it replaces the selected one). */
    val canAdd: Boolean get() = colors.size < MAX

    /** Selects dot [index]. */
    fun select(index: Int): Swatches = copy(selected = index.coerceIn(colors.indices))

    /** Selects [argb]: an existing dot, else a new dot at the end, else (full) the selected dot is replaced. */
    fun add(argb: Int): Swatches {
        val existing = colors.indexOf(argb)
        return when {
            existing >= 0 -> copy(selected = existing)
            canAdd -> Swatches((colors + argb).toImmutableList(), colors.size)
            else -> replace(selected, argb)
        }
    }

    /** Dot [index] becomes [argb] and is selected. */
    fun replace(
        index: Int,
        argb: Int,
    ): Swatches {
        require(index in colors.indices) { "index out of range" }
        return Swatches(colors.toMutableList().also { it[index] = argb }.toImmutableList(), index)
    }

    /** Removes dot [index] (the last dot stays). */
    fun remove(index: Int): Swatches {
        if (colors.size == 1 || index !in colors.indices) return this
        val next = colors.toMutableList().also { it.removeAt(index) }.toImmutableList()
        val keep = if (selected > index) selected - 1 else selected
        return Swatches(next, keep.coerceIn(next.indices))
    }

    /** Limits. */
    companion object {
        /** Most color dots per tool. */
        const val MAX = 12
    }
}

/** Small, medium and large width (pt) of one brush and the selected preset; long-press edits a preset. */
@Immutable
data class WidthPresets(
    val widthsPt: ImmutableList<Float>,
    val selected: Int = 1,
) {
    init {
        require(widthsPt.size == COUNT) { "$COUNT presets" }
        require(selected in widthsPt.indices) { "selected out of range" }
    }

    /** The selected width in pt. */
    val widthPt: Float get() = widthsPt[selected]

    /** Selects preset [index]. */
    fun select(index: Int): WidthPresets = copy(selected = index.coerceIn(widthsPt.indices))

    /** Preset [index] becomes [widthPt] (clamped to the custom width range). */
    fun set(
        index: Int,
        widthPt: Float,
    ): WidthPresets {
        require(index in widthsPt.indices) { "index out of range" }
        val clamped = BrushPresets.clampWidth(widthPt)
        return copy(widthsPt = widthsPt.toMutableList().also { it[index] = clamped }.toImmutableList())
    }

    /** Presets. */
    companion object {
        /** Presets per brush. */
        const val COUNT = 3

        /** The spec presets of [kind] (06-ink-input.md#brushes), medium selected. */
        fun of(kind: BrushKind): WidthPresets = WidthPresets(BrushPresets.widthsPt(kind).toImmutableList())
    }
}

/** Pen tool options (10-editor-ui.md#tool-options): every brush kind keeps its own widths; colors are shared. */
@Immutable
data class PenOptions(
    val kind: BrushKind = BrushKind.BALLPOINT,
    val widths: ImmutableMap<BrushKind, WidthPresets> = PEN_KINDS.associateWith(WidthPresets::of).toImmutableMap(),
    val swatches: Swatches = Swatches(BrushPresets.PEN_PALETTE.toImmutableList()),
    /** Pressure curve gamma (06-ink-input.md#brushes), normalized. */
    val pressureGamma: Float = 1f,
) {
    init {
        require(kind in PEN_KINDS) { "not a pen kind: $kind" }
        require(widths.keys.containsAll(PEN_KINDS)) { "widths for every pen kind" }
    }

    /** Width presets of the selected [kind]. */
    val kindWidths: WidthPresets get() = widths.getValue(kind)

    /** Replaces the width presets of the selected [kind]. */
    fun withKindWidths(presets: WidthPresets): PenOptions = copy(widths = (widths + (kind to presets)).toImmutableMap())

    /** Sets the pressure curve, clamped and quantized like the brush catalog does. */
    fun withPressureGamma(gamma: Float): PenOptions = copy(pressureGamma = PressureCurve.normalize(gamma))

    /** The brush new pen strokes get. */
    fun brush(version: Int): BrushSpec = BrushSpec(kind, swatches.argb, kindWidths.widthPt, version, pressureGamma)
}

/** Highlighter tool options; [alwaysStraight] is stored now, the canvas honors it with the straight-line snap (D-024, P07-T02). */
@Immutable
data class HighlighterOptions(
    val widths: WidthPresets = WidthPresets.of(BrushKind.HIGHLIGHTER),
    val swatches: Swatches = Swatches(BrushPresets.HIGHLIGHTER_PALETTE.toImmutableList()),
    val alwaysStraight: Boolean = false,
) {
    /** The brush new highlighter strokes get. */
    fun brush(version: Int): BrushSpec = BrushSpec(BrushKind.HIGHLIGHTER, swatches.argb, widths.widthPt, version)
}

/** Last options of every tool (persisted by [ToolOptionsStore]) plus the color picker's recent colors. */
@Immutable
data class ToolOptions(
    val pen: PenOptions = PenOptions(),
    val highlighter: HighlighterOptions = HighlighterOptions(),
    val eraser: EraserOptions = EraserOptions.DEFAULT,
    /** Most recent custom colors first, at most [MAX_RECENT_COLORS]. */
    val recentColors: ImmutableList<Int> = persistentListOf(),
) {
    /** [argb] moves to the front of [recentColors]. */
    fun withRecentColor(argb: Int): ToolOptions =
        copy(recentColors = (listOf(argb) + recentColors.filter { it != argb }).take(MAX_RECENT_COLORS).toImmutableList())

    /** Limits. */
    companion object {
        /** Recent colors the picker shows. */
        const val MAX_RECENT_COLORS = 8
    }
}
