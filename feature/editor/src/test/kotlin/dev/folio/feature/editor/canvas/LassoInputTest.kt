package dev.folio.feature.editor.canvas

import android.graphics.Bitmap
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.AssetId
import dev.folio.core.model.Attachment
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.RectF01
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.Applied
import dev.folio.core.model.edit.ReorderOp
import dev.folio.core.model.edit.SelectionEdit
import dev.folio.core.model.edit.command
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.lasso.LassoKind
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LassoInputTest {
    // 50 short strokes in a 10 x 5 grid at 100..550 x 100..300 pt, one stroke far away at (500, 700).
    private val grid = List(50) { i -> stroke("s$i", 100f + (i % 10) * 50f, 100f + (i / 10) * 50f) }
    private val far = stroke("far", 500f, 700f)
    private var doc: Document = ModelFixtures.document(listOf(ModelFixtures.page("p0", grid + far), ModelFixtures.page("p1")))
    private val pages = doc.pages
    private val page = pages[0].id
    private val viewport = Viewport(DENSITY).apply { setPages(pages, VIEW_W, VIEW_H) }
    private val edits = ArrayList<SelectionEdit>()
    private val applied = ArrayList<Applied>()
    private val hidden = ArrayList<LassoSelection?>()
    private var accept = true
    private var drawn = true
    private var snapshots = 0
    private var images = 0
    private val cropped = ArrayList<ObjectId>()
    private val opened = ArrayList<ObjectId>()
    private var spec = LassoSpec.DEFAULT
    private val clipboard = ObjectClipboard()

    private val host =
        object : LassoHost {
            override fun invalidate() = Unit

            override fun hide(selection: LassoSelection?) {
                hidden += selection
            }

            override fun snapshot(
                selection: LassoSelection,
                box: RectPt,
            ): LassoSnapshot = LassoSnapshot(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), box, 1f).also { snapshots++ }

            override fun edit(
                selection: LassoSelection,
                edit: SelectionEdit,
                done: (Boolean) -> Unit,
            ) {
                edits += edit
                if (accept) {
                    val result = edit.command(selection.pageId, selection.ids).execute(doc)
                    applied += result
                    doc = result.doc
                }
                done(accept)
            }

            override fun copyImage(
                pageId: PageId,
                objects: List<PageObject>,
            ) {
                images++
            }

            override fun editImage(
                pageId: PageId,
                id: ObjectId,
            ) {
                cropped += id
            }

            override fun openAttachment(
                pageId: PageId,
                id: ObjectId,
            ) {
                opened += id
            }

            override fun isDrawn(
                pageId: PageId,
                rect: RectPt,
            ) = drawn
        }

    private val input =
        LassoInput(
            viewport,
            pageIdAt = { pages.getOrNull(it)?.id },
            indexOf = { id -> pages.indexOfFirst { it.id == id } },
            objectsOn = { doc.page(it)?.objects },
            spec = { spec },
            clipboard = { clipboard },
            sizes = LassoSizes(HANDLE_PX, ROTATE_OFFSET_PX, MIN_SIDE_PX, SLOP_PX),
            host = host,
            toolbar = LassoToolbar(DENSITY),
        )

    @Test
    fun freeformLasso_aroundTheGrid_selectsThe50Strokes() {
        lasso(PointPt(80f, 80f), PointPt(600f, 80f), PointPt(600f, 330f), PointPt(80f, 330f))

        assertThat(input.selection?.ids).containsExactlyElementsIn(grid.map { it.id }).inOrder()
        assertThat(input.selection?.pageId).isEqualTo(page)
        assertThat(input.box()).isEqualTo(grid.fold(RectPt.EMPTY) { acc, s -> acc.union(s.bounds) })
        assertThat(edits).isEmpty()
    }

    @Test
    fun rectangleLasso_fromCornerToCorner_selectsTheStrokesInside() {
        spec = LassoSpec(rectangle = true, kinds = LassoKind.entries.toSet())

        lasso(PointPt(80f, 80f), PointPt(320f, 180f), PointPt(330f, 180f))

        // Columns x 100..300 of rows y 100 and 150: 5 x 2 strokes.
        assertThat(input.selection?.ids).hasSize(10)
        assertThat(input.rectangle).isTrue()
    }

    @Test
    fun filterWithoutInk_selectsNothing() {
        spec = LassoSpec(rectangle = false, kinds = setOf(LassoKind.SHAPES))

        lasso(PointPt(80f, 80f), PointPt(600f, 80f), PointPt(600f, 330f), PointPt(80f, 330f))

        assertThat(input.selection).isNull()
    }

    @Test
    fun dragInside_movesTheSelection_oneTransform_pendingUntilTheTilesShowIt() {
        selectGrid()
        drawn = false

        drag(PointPt(300f, 200f), PointPt(340f, 260f))

        val edit = edits.single() as SelectionEdit.Transform
        assertThat(edit.affine.tx).isWithin(0.01f).of(40f)
        assertThat(edit.affine.ty).isWithin(0.01f).of(60f)
        assertThat(snapshots).isEqualTo(1)
        assertThat(hidden.first()).isEqualTo(input.selection)
        val moved = doc.page(page)!!.objects.first { it.id == grid[0].id } as InkStroke
        assertThat(moved.inputs.x[0]).isWithin(0.01f).of(grid[0].inputs.x[0] + 40f)
        assertThat(input.pending).isNotNull() // tiles not drawn yet
        drawn = true
        input.check()
        assertThat(input.pending).isNull()
        assertThat(input.snapshot).isNull()
        // Undo of the one step restores every stroke.
        assertThat(
            applied
                .single()
                .inverse
                .execute(doc)
                .doc
                .page(page)!!
                .objects,
        ).isEqualTo(grid + far)
    }

    @Test
    fun rotateKnob_turnsAroundTheBoxCenter_snapsTo90() {
        selectGrid()
        val box = checkNotNull(input.box())
        val knob = PointPt(box.center.x, box.top - ROTATE_OFFSET_PX / viewport.scale)
        val c = box.center
        // A quarter turn clockwise, 2 degrees past: snaps onto 90.
        val r = c.y - knob.y
        val to = PointPt(c.x + r * kotlin.math.cos(Math.toRadians(2.0)).toFloat(), c.y + r * kotlin.math.sin(Math.toRadians(2.0)).toFloat())

        drag(knob, to)

        val affine = (edits.single() as SelectionEdit.Transform).affine
        assertThat(affine.a).isWithin(1e-4f).of(0f)
        assertThat(affine.b).isWithin(1e-4f).of(1f)
        assertThat(applied).hasSize(1)
    }

    @Test
    fun cornerHandle_scalesKeepingTheAspect() {
        selectGrid()
        val box = checkNotNull(input.box())

        drag(PointPt(box.right, box.bottom), PointPt(box.right + box.widthPt, box.bottom + box.heightPt))

        val affine = (edits.single() as SelectionEdit.Transform).affine
        assertThat(affine.a).isWithin(1e-3f).of(2f)
        assertThat(affine.d).isWithin(1e-3f).of(2f)
    }

    @Test
    fun rejectedMove_showsTheObjectsAgain() {
        selectGrid()
        accept = false

        drag(PointPt(300f, 200f), PointPt(340f, 260f))

        assertThat(input.pending).isNull()
        assertThat(hidden.last()).isNull()
        assertThat(input.selection).isNotNull()
    }

    @Test
    fun tapBeside_dropsTheSelection_thenOffersPasteWithAFilledClipboard() {
        selectGrid()
        press(LassoButton.COPY)
        assertThat(clipboard.objects).hasSize(50)
        assertThat(images).isEqualTo(1)

        tap(PointPt(300f, 600f))
        assertThat(input.selection).isNull()
        assertThat(input.pasteAt).isNull()
        tap(PointPt(300f, 600f))
        val (pastePage, at) = checkNotNull(input.pasteAt)
        assertThat(pastePage).isEqualTo(page)
        assertThat(at.x).isWithin(0.01f).of(300f)
        assertThat(at.y).isWithin(0.01f).of(600f)

        input.toolbar.place(viewport.toViewPx(page, PointPt(300f, 600f)).x, 1000f, 1000f, VIEW_W, VIEW_H, paste = true)
        press(LassoButton.PASTE)

        val pasted = (edits.single() as SelectionEdit.Add).objects
        assertThat(pasted).hasSize(50)
        assertThat(pasted.map { it.id }).containsNoneIn(grid.map { it.id })
        val bounds = pasted.fold(RectPt.EMPTY) { acc, o -> acc.union(o.bounds) }
        assertThat(bounds.center.x).isWithin(0.01f).of(300f)
        assertThat(bounds.center.y).isWithin(0.01f).of(600f)
        assertThat(input.selection?.ids).isEqualTo(pasted.map { it.id })
    }

    @Test
    fun actionBar_oneImageSelected_leadsWithCropWhichOpensTheCropScreen() {
        val image = ImageObject(ObjectId("img"), AssetId("a".repeat(64)), RectPt(100f, 400f, 300f, 500f), 0f, RectF01.FULL)
        doc = AddObjects(page, listOf(image)).execute(doc).doc

        input.select(LassoSelection(page, listOf(image.id)))
        input.box()
        assertThat(input.imageSelected).isTrue()
        press(LassoButton.CROP)
        assertThat(cropped).containsExactly(image.id)
        assertThat(edits).isEmpty()

        // With strokes alongside, the bar has no Crop.
        input.select(LassoSelection(page, listOf(image.id, grid[0].id)))
        input.box()
        assertThat(input.imageSelected).isFalse()
    }

    @Test
    fun tapOnAttachment_withImageKinds_selectsIt_barLeadsWithOpen() {
        val chip = Attachment(ObjectId("att"), AssetId("b".repeat(64)), "Syllabus.pdf", "application/pdf", PointPt(100f, 450f), 2048)
        doc = AddObjects(page, listOf(chip)).execute(doc).doc
        spec = LassoSpec(rectangle = false, kinds = setOf(LassoKind.IMAGES))

        tap(PointPt(150f, 470f))
        assertThat(input.selection).isEqualTo(LassoSelection(page, listOf(chip.id)))
        input.box()
        assertThat(input.leadButton).isEqualTo(LassoButton.OPEN)
        press(LassoButton.OPEN)
        assertThat(opened).containsExactly(chip.id)
        assertThat(edits).isEmpty()

        // A tap beside it drops the selection again.
        tap(PointPt(500f, 900f))
        assertThat(input.selection).isNull()
    }

    @Test
    fun tapOnAttachment_withoutImageKinds_selectsNothing() {
        val chip = Attachment(ObjectId("att"), AssetId("b".repeat(64)), "a.zip", "application/zip", PointPt(100f, 450f))
        doc = AddObjects(page, listOf(chip)).execute(doc).doc
        spec = LassoSpec(rectangle = false, kinds = setOf(LassoKind.INK))

        tap(PointPt(150f, 470f))
        assertThat(input.selection).isNull()
    }

    @Test
    fun actionBar_duplicateDeleteColorArrange_eachOneEdit() {
        selectGrid()
        press(LassoButton.DUPLICATE)
        val copies = (edits.last() as SelectionEdit.Add).objects
        assertThat(copies.first().bounds.left).isWithin(0.01f).of(grid.first().bounds.left + 16f)
        assertThat(input.selection?.ids).isEqualTo(copies.map { it.id })

        press(LassoButton.COLOR)
        assertThat(input.toolbar.popupKind).isEqualTo(LassoPopup.COLOR)
        pressItem(1)
        assertThat((edits.last() as SelectionEdit.Recolor).argb).isEqualTo(LassoToolbar.colorOf(1))

        press(LassoButton.ARRANGE)
        pressItem(3)
        assertThat((edits.last() as SelectionEdit.Reorder).op).isEqualTo(ReorderOp.SEND_TO_BACK)

        press(LassoButton.DELETE)
        assertThat(edits.last()).isEqualTo(SelectionEdit.Delete)
        assertThat(input.selection).isNull()
        assertThat(doc.page(page)!!.objects).isEqualTo(grid + far)
        assertThat(edits).hasSize(4)
    }

    @Test
    fun finger_insideTheBoxMoves_besideItPans() {
        selectGrid()
        val beside = viewport.toViewPx(page, PointPt(500f, 700f))
        assertThat(input.onFingerTouch(event(ACTION_DOWN, 0, beside.x to beside.y, toolType = TOOL_TYPE_FINGER))).isFalse()

        val from = viewport.toViewPx(page, PointPt(300f, 200f))
        val to = viewport.toViewPx(page, PointPt(310f, 200f))
        assertThat(input.onFingerTouch(event(ACTION_DOWN, 0, from.x to from.y, toolType = TOOL_TYPE_FINGER))).isTrue()
        assertThat(input.onFingerTouch(event(ACTION_MOVE, 10, to.x to to.y, toolType = TOOL_TYPE_FINGER))).isTrue()
        assertThat(input.onFingerTouch(event(ACTION_UP, 20, to.x to to.y, toolType = TOOL_TYPE_FINGER))).isTrue()

        assertThat((edits.single() as SelectionEdit.Transform).affine.tx).isWithin(0.01f).of(10f)
    }

    @Test
    fun undoneObjects_dropTheSelection() {
        selectGrid()
        doc = ModelFixtures.document(listOf(ModelFixtures.page("p0", listOf(far)), ModelFixtures.page("p1")))

        assertThat(input.box()).isNull()
        assertThat(input.selection).isNull()
    }

    private fun selectGrid() = lasso(PointPt(80f, 80f), PointPt(600f, 80f), PointPt(600f, 330f), PointPt(80f, 330f))

    private fun lasso(vararg points: PointPt) {
        val px = points.map { viewport.toViewPx(page, it) }
        input.onStylusDown(stylus(ACTION_DOWN, 0, px[0].x to px[0].y), 0, eraser = false)
        for (i in 1 until px.size) input.onStylusMove(stylus(ACTION_MOVE, i * 10L, px[i].x to px[i].y), 0)
        input.onStylusUp(stylus(ACTION_UP, px.size * 10L, px.last().x to px.last().y), 0)
    }

    private fun drag(
        from: PointPt,
        to: PointPt,
    ) {
        val a = viewport.toViewPx(page, from)
        val b = viewport.toViewPx(page, to)
        input.onStylusDown(stylus(ACTION_DOWN, 0, a.x to a.y), 0, eraser = false)
        input.onStylusMove(stylus(ACTION_MOVE, 10, b.x to b.y), 0)
        input.onStylusUp(stylus(ACTION_UP, 20, b.x to b.y), 0)
    }

    private fun tap(p: PointPt) {
        val a = viewport.toViewPx(page, p)
        input.onStylusDown(stylus(ACTION_DOWN, 0, a.x to a.y), 0, eraser = false)
        input.onStylusUp(stylus(ACTION_UP, 20, a.x to a.y), 0)
    }

    // A pen tap on [button] of the action bar, placed above the selection box like the overlay does.
    private fun press(button: LassoButton) {
        val toolbar = input.toolbar
        if (button != LassoButton.PASTE) {
            val box = checkNotNull(input.box())
            val top = viewport.toViewPx(page, PointPt(box.center.x, box.top))
            val bottom = viewport.toViewPx(page, PointPt(box.center.x, box.bottom))
            toolbar.place(top.x, top.y - ROTATE_OFFSET_PX, bottom.y, VIEW_W, VIEW_H, lead = input.leadButton)
        }
        val y = toolbar.pill.centerY()
        var x = toolbar.pill.left
        while (toolbar.buttonAt(x, y) != button) x += 1f
        tapPx(x + 10f, y)
    }

    private fun pressItem(item: Int) {
        val popup = input.toolbar.popup
        val x = popup.left + (popup.width() / checkNotNull(input.toolbar.popupKind).items) * (item + 0.5f)
        tapPx(x, popup.centerY())
    }

    private fun tapPx(
        x: Float,
        y: Float,
    ) {
        input.onStylusDown(stylus(ACTION_DOWN, 0, x to y), 0, eraser = false)
        input.onStylusUp(stylus(ACTION_UP, 20, x to y), 0)
    }

    private fun stylus(
        action: Int,
        timeMs: Long,
        px: Pair<Float, Float>,
    ) = event(action, timeMs, px, toolType = TOOL_TYPE_STYLUS)

    private companion object {
        const val DENSITY = 2.5f
        const val VIEW_W = 2136f
        const val VIEW_H = 3200f
        const val HANDLE_PX = 60f
        const val ROTATE_OFFSET_PX = 70f
        const val MIN_SIDE_PX = 20f
        const val SLOP_PX = 20f

        // A 4-point stroke of 20 x 10 pt at ([x], [y]).
        fun stroke(
            id: String,
            x: Float,
            y: Float,
        ): InkStroke {
            val inputs =
                StrokeInputs(
                    floatArrayOf(x, x + 8f, x + 14f, x + 20f),
                    floatArrayOf(y, y + 10f, y, y + 6f),
                    floatArrayOf(0f, 5f, 10f, 15f),
                    null,
                    null,
                    null,
                    InputTool.SYNTHETIC,
                )
            return InkStroke.of(ObjectId(id), BrushSpec(BrushKind.BALLPOINT, 0xFF1A1A1A.toInt(), 1f, 1), inputs)
        }
    }
}
