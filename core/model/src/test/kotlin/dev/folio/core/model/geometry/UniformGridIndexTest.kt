package dev.folio.core.model.geometry

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

class UniformGridIndexTest {
    @Test
    fun query_10kRandomRects_equalsBruteForce() {
        val random = Random(SEED)
        val index = UniformGridIndex<Int>()
        val all = HashMap<Int, RectPt>()
        repeat(10_000) { id ->
            val r = randomRect(random)
            all[id] = r
            index.insert(id, r)
        }
        // Mutations: remove 1000, move 1000.
        repeat(1000) {
            all.remove(it)
            assertThat(index.remove(it)).isTrue()
        }
        for (id in 1000 until 2000) {
            val r = randomRect(random)
            all[id] = r
            index.update(id, r)
        }
        assertThat(index.size).isEqualTo(all.size)
        repeat(300) { q ->
            val rect = randomRect(random, maxSize = if (q % 10 == 0) 3000f else 300f)
            val expected = all.filterValues { it.intersects(rect) }.keys
            assertWithMessage("seed=$SEED q=$q rect=$rect").that(index.query(rect)).containsExactlyElementsIn(expected)
        }
        repeat(300) { q ->
            val c = PointPt(random.nextFloat() * 3000f - 500f, random.nextFloat() * 3000f - 500f)
            val radius = random.nextFloat() * 60f
            val expected = all.filterValues { it.distanceTo(c.x, c.y) <= radius }.keys
            assertWithMessage("seed=$SEED q=$q c=$c r=$radius")
                .that(index.queryRadius(c, radius))
                .containsExactlyElementsIn(expected)
        }
    }

    @Test
    fun query_resultHasNoDuplicates() {
        val index = UniformGridIndex<String>()
        index.insert("big", RectPt(0f, 0f, 1000f, 1000f))
        assertThat(index.query(RectPt(0f, 0f, 1000f, 1000f))).containsExactly("big")
    }

    @Test
    fun oversizedAndFarAway_areFound() {
        val index = UniformGridIndex<String>(maxCellsPerItem = 4)
        index.insert("huge", RectPt(-1e7f, -1e7f, 1e7f, 1e7f))
        index.insert("far", RectPt(-90_000f, 50_000f, -89_990f, 50_010f))
        assertThat(index.query(RectPt(-90_005f, 50_005f, -90_000f, 50_006f))).containsExactly("huge", "far")
        assertThat(index.remove("huge")).isTrue()
        assertThat(index.query(RectPt(0f, 0f, 1f, 1f))).isEmpty()
    }

    @Test
    fun emptyBounds_storedButNeverMatch() {
        val index = UniformGridIndex<String>()
        index.insert("e", RectPt.EMPTY)
        assertThat(index.size).isEqualTo(1)
        assertThat(index.query(RectPt(-1e6f, -1e6f, 1e6f, 1e6f))).isEmpty()
        assertThat(index.remove("e")).isTrue()
        assertThat(index.remove("e")).isFalse()
    }

    private fun randomRect(
        random: Random,
        maxSize: Float = 200f,
    ): RectPt {
        val l = random.nextFloat() * 3000f - 500f
        val t = random.nextFloat() * 3000f - 500f
        return RectPt(l, t, l + random.nextFloat() * maxSize, t + random.nextFloat() * maxSize)
    }

    private companion object {
        const val SEED = 128
    }
}
