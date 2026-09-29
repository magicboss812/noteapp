package dev.folio.core.render

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import kotlinx.collections.immutable.toPersistentList
import org.junit.Test

class PageContentTest {
    private val a = dot("a", 10f, 10f)
    private val b = dot("b", 300f, 10f)
    private val c = dot("c", 12f, 12f)
    private val page = ModelFixtures.page("p", listOf(a, b, c))

    @Test
    fun objectsIn_returnsIntersectingZIndicesBottomFirst() {
        val content = PageContent.of(page)

        assertThat(content.objectsIn(RectPt(0f, 0f, 50f, 50f)).toList()).containsExactly(0, 2).inOrder()
        assertThat(content.objectsIn(RectPt(0f, 0f, 400f, 50f)).toList()).containsExactly(0, 1, 2).inOrder()
        assertThat(content.isEmptyIn(RectPt(100f, 100f, 200f, 200f))).isTrue()
    }

    @Test
    fun changedBounds_addRemoveReplace_coverOldAndNewBounds() {
        val moved = dot("b", 400f, 400f)
        val d = dot("d", 500f, 500f)
        val next = page.with(listOf(a, moved, d))

        assertThat(PageContent.changedBounds(page, next)).containsExactly(b.bounds, c.bounds, moved.bounds, d.bounds)
    }

    @Test
    fun changedBounds_zOrderSwap_coversSwappedObjectsOnly() {
        val next = page.with(listOf(a, c, b))

        assertThat(PageContent.changedBounds(page, next)).containsExactly(b.bounds, c.bounds, c.bounds, b.bounds)
    }

    @Test
    fun changedBounds_sameList_isEmpty() {
        assertThat(PageContent.changedBounds(page, page)).isEmpty()
        assertThat(PageContent.changedBounds(page, page.with(listOf(a, b, c)))).isEmpty()
    }

    @Test
    fun next_keepsMeshSlotsOfIdenticalObjectsOnly() {
        val content = PageContent.of(page)
        content.setMeshSlot(0, "mesh-a")
        content.setMeshSlot(1, "mesh-b")
        val replacedB = dot("b", 300f, 10f)

        val next = content.next(page.with(listOf(dot("new", 1f, 1f), a, replacedB, c)))

        assertThat(next.meshSlot(0)).isNull()
        assertThat(next.meshSlot(1)).isEqualTo("mesh-a")
        assertThat(next.meshSlot(2)).isNull() // equal but a different instance: rebuilt
        assertThat(next.size).isEqualTo(4)
        assertThat(content.next(page)).isSameInstanceAs(content)
    }

    private fun Page.with(objects: List<InkStroke>): Page = copy(objects = objects.toPersistentList())

    private fun dot(
        id: String,
        x: Float,
        y: Float,
    ): InkStroke {
        val inputs = StrokeInputs(floatArrayOf(x, x + 1f), floatArrayOf(y, y), floatArrayOf(0f, 5f), null, null, null, InputTool.SYNTHETIC)
        return InkStroke.of(ObjectId(id), BrushSpec(BrushKind.BALLPOINT, BLACK, 2f, 1), inputs)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
