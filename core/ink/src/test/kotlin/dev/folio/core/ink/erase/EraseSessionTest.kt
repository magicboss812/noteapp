package dev.folio.core.ink.erase

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.StrokeInputs
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric only for android.* classes; the host libink.so (meshes) comes from ink-nativeloader-jvm.
@RunWith(AndroidJUnit4::class)
class EraseSessionTest {
    private val a = line("a", y = 100f)
    private val between = line("between", y = 400f)
    private val h = line("h", y = 150f, kind = BrushKind.HIGHLIGHTER, sizePt = 12f)
    private val c = line("c", y = 200f)
    private val page = ModelFixtures.page("p1", listOf(a, between, h, c))
    private val doc = ModelFixtures.document(listOf(page))

    @Test
    fun strokeMode_verticalGesture_removesCrossedStrokesOnly() {
        val session = EraseSession(page, EraserOptions(EraserMode.STROKE, 4f))

        verticalGesture(session, x = 100f)

        val result = session.result()
        assertThat(result.replacements.keys).containsExactly(a.id, h.id, c.id).inOrder()
        assertThat(result.replacements.values.flatten()).isEmpty()
        assertThat(result.applyTo(page).objects).containsExactly(between)
    }

    @Test
    fun strokeMode_meshPrecision_nearMissKeptTouchErased() {
        // 1 pt ballpoint at y = 100 (mesh edge about 0.5 pt from the centerline), eraser radius 4 pt:
        // 4.8 pt away misses, 4.4 pt away touches, in both directions (ink alpha09 rotated-parallelogram bug).
        assertThat(hits(a, 20f, 104.8f, 180f, 104.8f)).isFalse()
        assertThat(hits(a, 180f, 104.8f, 20f, 104.8f)).isFalse()
        assertThat(hits(a, 20f, 104.4f, 180f, 104.4f)).isTrue()
        assertThat(hits(a, 180f, 104.4f, 20f, 104.4f)).isTrue()

        // Vertical stroke at x = 100 against vertical gestures down and up.
        val vInputs =
            StrokeInputs(
                FloatArray(201) { 100f },
                FloatArray(201) { it.toFloat() },
                FloatArray(201) { it * 2f },
                null,
                null,
                null,
                InputTool.SYNTHETIC,
            )
        val v = InkStroke.of(ObjectId("v"), a.brush, vInputs)
        assertThat(hits(v, 104.8f, 20f, 104.8f, 180f)).isFalse()
        assertThat(hits(v, 104.8f, 180f, 104.8f, 20f)).isFalse()
        assertThat(hits(v, 104.4f, 20f, 104.4f, 180f)).isTrue()
        assertThat(hits(v, 104.4f, 180f, 104.4f, 20f)).isTrue()
    }

    private fun hits(
        stroke: InkStroke,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Boolean {
        val session = EraseSession(ModelFixtures.page("p", listOf(stroke)), EraserOptions(EraserMode.STROKE, 4f))
        session.moveTo(ax, ay)
        session.moveTo(bx, by)
        return !session.result().isEmpty
    }

    @Test
    fun strokeMode_firstMoveErasesADot() {
        val session = EraseSession(page, EraserOptions(EraserMode.STROKE, 4f))

        assertThat(session.moveTo(60f, 101f)).isTrue()
        assertThat(session.result().replacements.keys).containsExactly(a.id)
    }

    @Test
    fun highlighterOnly_sparesOtherBrushes() {
        val session = EraseSession(page, EraserOptions(EraserMode.STROKE, 4f, highlighterOnly = true))

        verticalGesture(session, x = 100f)

        assertThat(session.result().replacements.keys).containsExactly(h.id)
    }

    @Test
    fun partialMode_splitsInPlace_zOrderKept() {
        val session = EraseSession(page, EraserOptions(EraserMode.PARTIAL, 4f))

        verticalGesture(session, x = 100f)

        val objects =
            session
                .result()
                .applyTo(page)
                .objects
                .map { it as InkStroke }
        // a, between, h, c -> a1 a2, between, h1 h2, c1 c2
        assertThat(objects).hasSize(7)
        assertThat(objects[2]).isSameInstanceAs(between)
        assertThat(objects.map { it.inputs.y[0] }).containsExactly(100f, 100f, 400f, 150f, 150f, 200f, 200f).inOrder()
        assertThat(objects.map { it.brush }).containsExactly(a.brush, a.brush, between.brush, h.brush, h.brush, c.brush, c.brush).inOrder()
        assertThat(objects.map { it.id }.toSet()).hasSize(7)
        // Reach = radius 4 + half width 0.5: inputs 96..104 go.
        assertThat(objects[0].inputs.x.last()).isEqualTo(95f)
        assertThat(objects[1].inputs.x.first()).isEqualTo(105f)
        // The 12 pt highlighter loses 90..110 (reach 4 + 6).
        assertThat(objects[3].inputs.x.last()).isEqualTo(89f)
        assertThat(objects[4].inputs.x.first()).isEqualTo(111f)
    }

    @Test
    fun partialMode_secondPassSplitsFragments_underOneOriginal() {
        val session = EraseSession(ModelFixtures.page("p", listOf(a)), EraserOptions(EraserMode.PARTIAL, 4f))

        verticalGesture(session, x = 50f)
        session.moveTo(150f, 250f) // along y = 250, away from the stroke
        verticalGesture(session, x = 150f)

        val pieces = session.result().replacements.getValue(a.id)
        assertThat(pieces.map { it.inputs.x.first() to it.inputs.x.last() })
            .containsExactly(0f to 45f, 55f to 145f, 155f to 200f)
            .inOrder()
    }

    @Test
    fun command_isOneUndoStep_undoRestoresExactly() {
        val session = EraseSession(page, EraserOptions(EraserMode.PARTIAL, 4f))
        verticalGesture(session, x = 100f)
        val result = session.result()

        val applied = result.command().execute(doc)
        val body = checkNotNull(applied.doc.pageBodies[page.id])
        // The command produces exactly the preview (same instances, same order).
        val preview = result.applyTo(page).objects
        assertThat(body.objects.size).isEqualTo(preview.size)
        assertThat(body.objects.indices.all { body.objects[it] === preview[it] }).isTrue()

        val undone = applied.inverse.execute(applied.doc).doc
        assertThat(undone).isEqualTo(doc)
        assertThat(undone.pageBodies[page.id]?.objects).containsExactly(a, between, h, c).inOrder()
    }

    @Test
    fun strokeMode_undoRestoresZOrder() {
        val session = EraseSession(page, EraserOptions(EraserMode.STROKE, 4f))
        verticalGesture(session, x = 100f)

        val applied = session.result().command().execute(doc)

        assertThat(applied.doc.pageBodies[page.id]?.objects).containsExactly(between)
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(doc)
    }

    /** Eraser from y = 50 down to y = 250 at [x] in 10 pt steps. */
    private fun verticalGesture(
        session: EraseSession,
        x: Float,
    ) {
        var y = 50f
        while (y <= 250f) {
            session.moveTo(x, y)
            y += 10f
        }
    }

    private fun line(
        id: String,
        y: Float,
        kind: BrushKind = BrushKind.BALLPOINT,
        sizePt: Float = 1f,
    ): InkStroke {
        val inputs =
            StrokeInputs(
                x = FloatArray(201) { it.toFloat() },
                y = FloatArray(201) { y },
                tMs = FloatArray(201) { it * 2f },
                pressure = null,
                tiltDeg = null,
                orientationDeg = null,
                tool = InputTool.SYNTHETIC,
            )
        return InkStroke.of(ObjectId(id), BrushSpec(kind, BLACK, sizePt, 1), inputs)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
