package dev.folio.core.ink.erase

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.InputTool
import dev.folio.core.model.StrokeInputs
import org.junit.Test

class StrokeSplitterTest {
    /** Horizontal line y = 0 from x = 0 to 100, one input per pt, t = 2x ms, all channels. */
    private val line =
        StrokeInputs(
            x = FloatArray(101) { it.toFloat() },
            y = FloatArray(101),
            tMs = FloatArray(101) { it * 2f },
            pressure = FloatArray(101) { it / 100f },
            tiltDeg = FloatArray(101) { it * 0.5f },
            orientationDeg = FloatArray(101) { it.toFloat() },
            tool = InputTool.STYLUS,
        )

    @Test
    fun split_eraserMisses_returnsNull() {
        assertThat(StrokeSplitter.split(line, 0f, 20f, 100f, 20f, 5f)).isNull()
    }

    @Test
    fun split_verticalCrossing_twoFragmentsWithRebasedTimesAndSlicedChannels() {
        val parts = checkNotNull(StrokeSplitter.split(line, 50f, -20f, 50f, 20f, 5f))

        assertThat(parts).hasSize(2)
        val (left, right) = parts
        assertThat(left.x.first()).isEqualTo(0f)
        assertThat(left.x.last()).isEqualTo(44f)
        assertThat(right.x.first()).isEqualTo(56f)
        assertThat(right.x.last()).isEqualTo(100f)
        assertThat(right.tMs.first()).isEqualTo(0f)
        assertThat(right.tMs.last()).isEqualTo(88f)
        assertThat(right.pressure?.first()).isEqualTo(0.56f)
        assertThat(right.tiltDeg?.first()).isEqualTo(28f)
        assertThat(right.orientationDeg?.first()).isEqualTo(56f)
        assertThat(right.tool).isEqualTo(InputTool.STYLUS)
    }

    @Test
    fun split_eraserCoversEverything_returnsEmpty() {
        assertThat(StrokeSplitter.split(line, 0f, 0f, 100f, 0f, 1f)).isEmpty()
    }

    @Test
    fun split_leftoversBelowMinimums_dropped() {
        // Removes x in 1..99: a single input remains on each side.
        assertThat(StrokeSplitter.split(line, 1f, 0f, 99f, 0f, 0.5f)).isEmpty()
        // Removes x in 2..98: two inputs 1 pt apart remain on each side (exactly the minimum length).
        assertThat(StrokeSplitter.split(line, 2f, 0f, 98f, 0f, 0.5f)).hasSize(2)
    }

    @Test
    fun split_shortRunBelowOnePt_dropped() {
        val dense =
            StrokeInputs(
                x = floatArrayOf(0f, 0.4f, 0.8f, 50f, 50.4f),
                y = FloatArray(5),
                tMs = floatArrayOf(0f, 1f, 2f, 3f, 4f),
                pressure = null,
                tiltDeg = null,
                orientationDeg = null,
                tool = InputTool.SYNTHETIC,
            )
        // Removes x = 50 and 50.4 and cuts nothing else: the 0.8 pt run left over is too short.
        assertThat(StrokeSplitter.split(dense, 50f, -5f, 50f, 5f, 1f)).isEmpty()
    }

    @Test
    fun split_sparseSegmentCrossedBetweenInputs_isCut() {
        val sparse =
            StrokeInputs(
                x = floatArrayOf(0f, 10f, 90f, 100f),
                y = FloatArray(4),
                tMs = floatArrayOf(0f, 5f, 45f, 50f),
                pressure = null,
                tiltDeg = null,
                orientationDeg = null,
                tool = InputTool.SYNTHETIC,
            )
        val parts = checkNotNull(StrokeSplitter.split(sparse, 50f, -20f, 50f, 20f, 2f))

        assertThat(parts.map { it.x.toList() }).containsExactly(listOf(0f, 10f), listOf(90f, 100f)).inOrder()
        assertThat(parts[1].tMs.toList()).containsExactly(0f, 5f).inOrder()
    }

    @Test
    fun segmentDistance_crossingSegments_zero() {
        assertThat(StrokeSplitter.segmentDistance(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f)).isEqualTo(0f)
        assertThat(StrokeSplitter.segmentDistance(0f, 0f, 10f, 0f, 0f, 3f, 10f, 3f)).isEqualTo(3f)
        assertThat(StrokeSplitter.pointSegmentDistance(5f, 5f, 0f, 0f, 0f, 0f)).isWithin(1e-5f).of(7.0710678f)
    }
}
