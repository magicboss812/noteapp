@file:OptIn(ExperimentalInkCustomBrushApi::class)
// Behavior ranges are the spec table of 06-ink-input.md#brushes; naming each would hide the table.
@file:Suppress("MagicNumber")

package dev.folio.core.ink.brush

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.behavior.BinaryOpNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.OutOfRange
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ValueNode
import androidx.ink.geometry.ImmutableVec
import dev.folio.core.model.BrushKind

/**
 * Version 1 of the brush families (06-ink-input.md#brushes). Frozen: see [BrushDefinitions].
 * Brush size is the nominal line width in pt; behaviors scale it.
 */
internal object BrushDefinitionsV1 : BrushDefinitions {
    override val version: Int = 1

    /** Highlighter color opacity (the stored color stays opaque). */
    const val HIGHLIGHTER_OPACITY = 0.35f

    private const val FOUNTAIN_NIB_DEG = 30f
    private const val FOUNTAIN_NIB_ASPECT = 0.6f
    private const val HIGHLIGHTER_CHISEL_ASPECT = 0.3f
    private const val HIGHLIGHTER_CORNER_ROUNDING = 0.25f
    private const val PENCIL_GRAIN_SIZE_PT = 8f

    // Tilt from about 20 deg (upright) to 69 deg (the Focus Pen maximum, device.md#stylus).
    private const val TILT_START_RAD = 0.35f
    private const val TILT_END_RAD = 1.2f

    // Pinned (not DEFAULT_INPUT_MODEL): a library default change must not alter v1 strokes.
    private const val INPUT_WINDOW_MS = 20L
    private const val INPUT_UPSAMPLING_HZ = 180

    override fun usesPressure(kind: BrushKind): Boolean =
        kind == BrushKind.BALLPOINT || kind == BrushKind.FOUNTAIN || kind == BrushKind.PENCIL

    override fun usesTilt(kind: BrushKind): Boolean = kind == BrushKind.PENCIL

    override fun family(
        kind: BrushKind,
        gamma: Float,
        tilt: Boolean,
    ): BrushFamily {
        val (tip, paint) =
            when (kind) {
                BrushKind.BALLPOINT -> ballpoint(gamma)
                BrushKind.FOUNTAIN -> fountain(gamma)
                BrushKind.PENCIL -> pencil(gamma, tilt)
                BrushKind.MARKER -> marker()
                BrushKind.HIGHLIGHTER -> highlighter()
            }
        return BrushFamily(
            tip = tip,
            paint = paint,
            inputModel = BrushFamily.InputModel.SlidingWindowModel(INPUT_WINDOW_MS, INPUT_UPSAMPLING_HZ),
            developerComment = "folio ${kind.name.lowercase()} v$version",
        )
    }

    /** Light pressure response (width 85-100%), no speed thinning. */
    private fun ballpoint(gamma: Float): Pair<BrushTip, BrushPaint> =
        BrushTip(behaviors = listOf(predictionFade(), pressureTo(TargetNode.Target.SIZE_MULTIPLIER, 0.85f, 1f, gamma))) to
            BrushPaint()

    /** Strong pressure response (width 35-120%) and an elliptic nib at 30 deg for direction-dependent width. */
    private fun fountain(gamma: Float): Pair<BrushTip, BrushPaint> =
        BrushTip(
            scaleX = 1f,
            scaleY = FOUNTAIN_NIB_ASPECT,
            rotationDegrees = FOUNTAIN_NIB_DEG,
            behaviors = listOf(predictionFade(), pressureTo(TargetNode.Target.SIZE_MULTIPLIER, 0.35f, 1.2f, gamma)),
        ) to BrushPaint()

    /** Paper grain texture; pressure -> opacity 35-90% and width 90-110%; tilt widens and lightens. */
    private fun pencil(
        gamma: Float,
        tilt: Boolean,
    ): Pair<BrushTip, BrushPaint> {
        val behaviors =
            mutableListOf(
                predictionFade(),
                pressureTo(TargetNode.Target.OPACITY_MULTIPLIER, 0.35f, 0.9f, gamma),
                pressureTo(TargetNode.Target.SIZE_MULTIPLIER, 0.9f, 1.1f, gamma),
            )
        if (tilt) {
            behaviors += tiltTo(TargetNode.Target.SIZE_MULTIPLIER, 1f, 2.5f)
            behaviors += tiltTo(TargetNode.Target.OPACITY_MULTIPLIER, 1f, 0.55f)
        }
        val grain =
            BrushPaint.TilingTexture(
                clientTextureId = BrushTextures.PENCIL_GRAIN_V1,
                sizeX = PENCIL_GRAIN_SIZE_PT,
                sizeY = PENCIL_GRAIN_SIZE_PT,
                blendMode = BrushPaint.TextureLayer.BlendMode.MODULATE,
            )
        return BrushTip(behaviors = behaviors) to BrushPaint(textureLayers = listOf(grain))
    }

    /** Constant width, opaque, round tip. */
    private fun marker(): Pair<BrushTip, BrushPaint> = BrushTip(behaviors = listOf(predictionFade())) to BrushPaint()

    /** Chisel tip, 35% opacity, self-overlap discarded (crossing a line does not darken it). */
    private fun highlighter(): Pair<BrushTip, BrushPaint> =
        BrushTip(
            scaleX = HIGHLIGHTER_CHISEL_ASPECT,
            scaleY = 1f,
            cornerRounding = HIGHLIGHTER_CORNER_ROUNDING,
            behaviors = listOf(predictionFade()),
        ) to
            BrushPaint(
                textureLayers = emptyList(),
                colorFunctions = listOf(BrushPaint.ColorFunction.OpacityMultiplier(HIGHLIGHTER_OPACITY)),
                selfOverlap = SelfOverlap.DISCARD,
            )

    private fun pressureTo(
        target: TargetNode.Target,
        start: Float,
        end: Float,
        gamma: Float,
    ): BrushBehavior = BrushBehavior(TargetNode(target, start, end, pressure(gamma)))

    private fun tiltTo(
        target: TargetNode.Target,
        start: Float,
        end: Float,
    ): BrushBehavior =
        BrushBehavior(
            TargetNode(target, start, end, SourceNode(SourceNode.Source.TILT_IN_RADIANS, TILT_START_RAD, TILT_END_RAD, OutOfRange.CLAMP)),
        )

    /** Normalized pressure through the `p^gamma` curve; inputs without pressure skip the behavior. */
    private fun pressure(gamma: Float): ValueNode {
        val source = SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f, OutOfRange.CLAMP)
        if (PressureCurve.isLinear(gamma)) return source
        val p = PressureCurve.points(gamma)
        val curve = EasingFunction.Linear(List(PressureCurve.SAMPLES) { ImmutableVec(p[it * 2], p[it * 2 + 1]) })
        return ResponseNode(curve, source)
    }

    /** Same fade as the androidx.ink stock brushes: predicted input fades out the farther ahead it is. */
    private fun predictionFade(): BrushBehavior =
        BrushBehavior(
            TargetNode(
                TargetNode.Target.OPACITY_MULTIPLIER,
                1f,
                0.3f,
                BinaryOpNode(
                    BinaryOpNode.BinaryOp.PRODUCT,
                    SourceNode(SourceNode.Source.PREDICTED_TIME_ELAPSED_IN_SECONDS, 0f, 0.024f, OutOfRange.CLAMP),
                    ResponseNode(
                        EasingFunction.Predefined.EASE_IN_OUT,
                        SourceNode(SourceNode.Source.PREDICTED_DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 1.5f, 2f, OutOfRange.CLAMP),
                    ),
                ),
            ),
        )
}
