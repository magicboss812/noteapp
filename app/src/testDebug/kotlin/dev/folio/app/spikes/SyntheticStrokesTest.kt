package dev.folio.app.spikes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyntheticStrokesTest {
    private val strokes = SyntheticStrokes.generate(widthPt = 595.28f, heightPt = 841.89f)

    @Test
    fun generate_defaultSeed_is1500StrokesAndDeterministic() {
        val again = SyntheticStrokes.generate(widthPt = 595.28f, heightPt = 841.89f)

        assertThat(strokes).hasSize(1500)
        assertThat(again.map { it.xsPt.toList() }).isEqualTo(strokes.map { it.xsPt.toList() })
        assertThat(again.map { it.argb }).isEqualTo(strokes.map { it.argb })
    }

    @Test
    fun generate_everyStroke_staysOnPageWithValidInputs() {
        for (s in strokes) {
            assertThat(s.xsPt.size).isIn(20..80)
            assertThat(s.ysPt.size).isEqualTo(s.xsPt.size)
            assertThat(s.xsPt.all { it > 0f && it < 595.28f }).isTrue()
            assertThat(s.ysPt.all { it > 0f && it < 841.89f }).isTrue()
            assertThat(s.pressures.all { it in 0.3f..1f }).isTrue()
        }
    }

    @Test
    fun generate_brushMix_isRoughlySixtyTwentyFiveFifteen() {
        val counts = strokes.groupingBy { it.brush }.eachCount()

        assertThat(counts.getValue(SyntheticBrush.PEN)).isIn(825..975)
        assertThat(counts.getValue(SyntheticBrush.MARKER)).isIn(300..450)
        assertThat(counts.getValue(SyntheticBrush.HIGHLIGHTER)).isIn(150..300)
    }
}
