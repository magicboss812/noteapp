package dev.folio.feature.editor.state

import androidx.compose.runtime.Immutable
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.ink.brush.PressureCurve
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.TextAlign
import dev.folio.core.text.fonts.BodySize
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.fonts.TextRole
import dev.folio.core.text.fonts.TextSizing
import dev.folio.feature.editor.canvas.ShapeSpec
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** Path a lasso gesture draws (10-editor-ui.md#tool-options). */
enum class LassoMode {
    /** Follows the pen. */
    FREEFORM,

    /** Spans a rectangle from the first to the last point. */
    RECTANGLE,
}

/** Object kinds a lasso may select (R-INK-04). */
enum class LassoFilter {
    INK,
    SHAPES,
    TEXT,
    IMAGES,
}

/** Lasso tool options; stored now, the lasso (P07-T03) applies them. */
@Immutable
data class LassoOptions(
    val mode: LassoMode = LassoMode.FREEFORM,
    val filters: ImmutableSet<LassoFilter> = LassoFilter.entries.toImmutableSet(),
) {
    init {
        require(filters.isNotEmpty()) { "at least one lasso filter" }
    }

    /** Turns [filter] on or off; the last filter on stays on, so the lasso always selects something. */
    fun toggle(filter: LassoFilter): LassoOptions =
        when {
            filter !in filters -> copy(filters = (filters + filter).toImmutableSet())
            filters.size == 1 -> this
            else -> copy(filters = (filters - filter).toImmutableSet())
        }
}

/**
 * Text tool options (10-editor-ui.md#tool-options): font family, body size and baseline lift of new text flows.
 * [bodyCap] is the body cap height as a fraction of U, a [BodySize] preset or a free value (A-039), kept on 0.01
 * steps; [baselineLift] is how far baselines sit above the rules in U (A-054), kept on [FlowStyle] lift steps.
 */
@Immutable
data class TextOptions(
    val fontFamily: String = FontRegistry.DEFAULT_FAMILY,
    val bodyCap: Float = BodySize.M.capFraction,
    val baselineLift: Float = 0f,
) {
    init {
        require(fontFamily.isNotBlank()) { "font family name" }
        require(bodyCap in TextSizing.MIN_BODY_CAP..TextSizing.MAX_BODY_CAP) { "body size out of range" }
        require(baselineLift in 0f..FlowStyle.MAX_BASELINE_LIFT) { "baseline lift out of range" }
    }

    /** Lift [lift] in U, clamped to 0..0.35 and rounded to a 0.05 step. */
    fun withBaselineLift(lift: Float): TextOptions = copy(baselineLift = FlowStyle.clampBaselineLift(lift))

    /** The preset [bodyCap] equals, null for a free size. */
    val preset: BodySize? get() = BodySize.entries.firstOrNull { abs(it.capFraction - bodyCap) < SIZE_STEP / 2 }

    /** Height of a body line box in grid lines (1 for S/M/L, up to 3 for the largest sizes). */
    val bodyLines: Int get() = TextSizing.boxUnits(TextRole.BODY, bodyCap)

    /** Size [cap], clamped to the free range and rounded to a 0.01 step. */
    fun withBodyCap(cap: Float): TextOptions {
        // Divide (not multiply by SIZE_STEP): n / 100f is the float nearest n/100, so the range ends stay inside.
        val rounded = (TextSizing.clampBodyCap(cap) * STEPS_PER_UNIT).roundToInt() / STEPS_PER_UNIT
        return copy(bodyCap = rounded.coerceIn(TextSizing.MIN_BODY_CAP, TextSizing.MAX_BODY_CAP))
    }

    /** The style new flows get. */
    fun flowStyle(): FlowStyle = FlowStyle(fontFamily, bodyCap, 0, TextAlign.START, baselineLift)

    /** Steps. */
    companion object {
        /** Free size step (cap fraction of U). */
        const val SIZE_STEP = 0.01f
        private const val STEPS_PER_UNIT = 100f
    }
}

/** Inside of a new shape (06-ink-input.md#shapes): none, the outline color at 25 %, or the outline color. */
enum class ShapeFill(
    internal val alpha: Int,
) {
    NONE(0),
    TINT(0x40),
    SOLID(0xFF),
    ;

    /** Fill color for outline color [argb], null for [NONE]. */
    fun argb(argb: Int): Int? = if (this == NONE) null else (argb and RGB_MASK) or (alpha shl ALPHA_SHIFT)

    private companion object {
        const val RGB_MASK = 0x00FFFFFF
        const val ALPHA_SHIFT = 24
    }
}

// Thin, medium and thick shape outlines (pt).
private val SHAPE_WIDTHS_PT = persistentListOf(1f, 2f, 4f)

/** Shape kinds in options-row order. */
val SHAPE_KINDS: ImmutableList<ShapeKind> = ShapeKind.entries.toImmutableList()

/**
 * Shape tool options (10-editor-ui.md#tool-options): kind, outline widths, dashes, fill and the constraint
 * (15 deg angles, equal sides). Colors are the pen's dots.
 */
@Immutable
data class ShapeOptions(
    val kind: ShapeKind = ShapeKind.RECTANGLE,
    val widths: WidthPresets = WidthPresets(SHAPE_WIDTHS_PT),
    val dashed: Boolean = false,
    val fill: ShapeFill = ShapeFill.NONE,
    val constrain: Boolean = false,
) {
    /** What the canvas draws with outline color [argb]. */
    fun spec(argb: Int): ShapeSpec = ShapeSpec(kind, StrokeStyle(argb, widths.widthPt, dashed), fill.argb(argb), constrain)
}

/** Last options of every tool (persisted by [ToolOptionsStore]) plus the color picker's recent colors. */
@Immutable
data class ToolOptions(
    val pen: PenOptions = PenOptions(),
    val highlighter: HighlighterOptions = HighlighterOptions(),
    val eraser: EraserOptions = EraserOptions.DEFAULT,
    /** Most recent custom colors first, at most [MAX_RECENT_COLORS]. */
    val recentColors: ImmutableList<Int> = persistentListOf(),
    val lasso: LassoOptions = LassoOptions(),
    val text: TextOptions = TextOptions(),
    val shape: ShapeOptions = ShapeOptions(),
) {
    /** What the Shape tool draws: its options with the pen's selected color. */
    val shapeSpec: ShapeSpec get() = shape.spec(pen.swatches.argb)

    /** [argb] moves to the front of [recentColors]. */
    fun withRecentColor(argb: Int): ToolOptions =
        copy(recentColors = (listOf(argb) + recentColors.filter { it != argb }).take(MAX_RECENT_COLORS).toImmutableList())

    /** Color dots of [tool]: the highlighter's own, the pen's for every other tool. */
    fun swatches(tool: EditorTool): Swatches = if (tool == EditorTool.HIGHLIGHTER) highlighter.swatches else pen.swatches

    /** Replaces the color dots of [tool] (see [swatches]). */
    fun withSwatches(
        tool: EditorTool,
        swatches: Swatches,
    ): ToolOptions =
        if (tool == EditorTool.HIGHLIGHTER) {
            copy(highlighter = highlighter.copy(swatches = swatches))
        } else {
            copy(pen = pen.copy(swatches = swatches))
        }

    /** Color picker result for [tool]: dot [index] becomes [argb] (null or a stale index: added); [argb] becomes recent. */
    fun withPickedColor(
        tool: EditorTool,
        index: Int?,
        argb: Int,
    ): ToolOptions {
        val dots = swatches(tool)
        val next = if (index != null && index in dots.colors.indices) dots.replace(index, argb) else dots.add(argb)
        return withSwatches(tool, next).withRecentColor(argb)
    }

    /** Width presets of [tool]: the highlighter's, the shape outline's, else those of the selected pen kind. */
    fun widths(tool: EditorTool): WidthPresets =
        when (tool) {
            EditorTool.HIGHLIGHTER -> highlighter.widths
            EditorTool.SHAPE -> shape.widths
            else -> pen.kindWidths
        }

    /** Replaces the width presets of [tool] (see [widths]). */
    fun withWidths(
        tool: EditorTool,
        presets: WidthPresets,
    ): ToolOptions =
        when (tool) {
            EditorTool.HIGHLIGHTER -> copy(highlighter = highlighter.copy(widths = presets))
            EditorTool.SHAPE -> copy(shape = shape.copy(widths = presets))
            else -> copy(pen = pen.withKindWidths(presets))
        }

    /** Limits. */
    companion object {
        /** Recent colors the picker shows. */
        const val MAX_RECENT_COLORS = 8
    }
}
