package dev.folio.feature.editor.state

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.getOrNull
import dev.folio.core.model.Attachment
import dev.folio.core.model.Document
import dev.folio.core.model.ImageObject
import dev.folio.core.model.PageId
import dev.folio.core.model.RectF01
import dev.folio.core.model.lasso.LassoKind
import dev.folio.core.render.viewport.visibleRectPt
import dev.folio.feature.editor.canvas.CanvasTool
import dev.folio.feature.editor.canvas.ObjectSelection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory

/** Images and attachments through the EditorViewModel (P07-T04, P07-T07): insert, select, edit or open, undo, reopen. */
@RunWith(AndroidJUnit4::class)
class EditorMediaViewModelTest : EditorViewModelTestBase() {
    @Test
    fun images_insertSelectCropTurnUndo_reopenKeepsImageAndAsset() =
        editorTest {
            val path = createDoc("Images")
            val vm = viewModel(path)
            val session = vm.opened()
            session.viewport.setPages(session.document.value.pages, VIEW_W, VIEW_H)
            val page =
                session.document.value.pages[0]
                    .id
            vm.selectTool(EditorTool.IMAGE)
            vm.awaitState { it.tool == EditorTool.IMAGE }
            assertThat(session.activeTool).isEqualTo(CanvasTool.LASSO)
            assertThat(session.lassoSpec.kinds).containsExactly(LassoKind.IMAGES)
            val selected = async(start = CoroutineStart.UNDISPATCHED) { session.selections.first() }
            val png = ImageImport.fromBytes(pngBytes(400, 200), "photo.png").getOrNull()!!

            assertThat(vm.insertImage(png)).isTrue()
            val image =
                session.document.value
                    .images(page)
                    .single()
            assertThat(selected.await()).isEqualTo(ObjectSelection(page, listOf(image.id)))
            assertThat(session.document.value.assets.keys).containsExactly(image.asset)
            // Centered in the visible part of the page, aspect kept.
            val visible = session.viewport.visibleRectPt(page)
            assertThat(image.rect.center.x).isWithin(0.5f).of(visible.center.x)
            assertThat(image.rect.widthPt / image.rect.heightPt).isWithin(1e-3f).of(2f)

            session.editImage(page, image.id)
            assertThat(vm.awaitState { it.imageEdit != null }.imageEdit!!.crop).isEqualTo(RectF01.FULL)
            vm.applyImageEdit(ImageCropResult(RectF01(0.5f, 0f, 1f, 1f), 1))
            vm.awaitState { it.imageEdit == null }
            val cropped =
                session.document.value
                    .images(page)
                    .single()
            assertThat(cropped.crop).isEqualTo(RectF01(0.5f, 0f, 1f, 1f))
            assertThat(cropped.rotationDeg).isEqualTo(90f)
            assertThat(cropped.rect.widthPt).isWithin(1e-3f).of(image.rect.widthPt / 2)

            assertThat(session.undo()).isTrue()
            assertThat(
                session.document.value
                    .images(page)
                    .single(),
            ).isEqualTo(image)
            assertThat(session.undo()).isTrue()
            assertThat(session.document.value.images(page)).isEmpty()
            assertThat(session.document.value.assets).isEmpty()
            assertThat(session.redo()).isTrue()
            assertThat(session.redo()).isTrue()

            store.clear()
            awaitReleases()
            val reopened = viewModel(path).opened()
            reopened.documentSession.loadPages(listOf(page))
            assertThat(
                reopened.document.value
                    .images(page)
                    .single(),
            ).isEqualTo(cropped)
            assertThat(reopened.documentSession.readAsset(image.asset).getOrNull()).isEqualTo(png.bytes)
        }

    @Test
    fun attachments_insertSelectOpenUndo_reopenKeepsChipAndBytes() =
        editorTest {
            val path = createDoc("Attachments")
            val vm = viewModel(path)
            val session = vm.opened()
            session.viewport.setPages(session.document.value.pages, VIEW_W, VIEW_H)
            val page =
                session.document.value.pages[0]
                    .id
            vm.selectTool(EditorTool.ATTACHMENT)
            vm.awaitState { it.tool == EditorTool.ATTACHMENT }
            assertThat(session.activeTool).isEqualTo(CanvasTool.LASSO)
            assertThat(session.lassoSpec.kinds).containsExactly(LassoKind.IMAGES)
            val selected = async(start = CoroutineStart.UNDISPATCHED) { session.selections.first() }
            val bytes = "%PDF-1.4\n% fixture\n".toByteArray()

            assertThat(vm.insertAttachment(ImportedFile(bytes, "application/pdf", "Syllabus.pdf"))).isTrue()
            val chip =
                session.document.value
                    .attachments(page)
                    .single()
            assertThat(selected.await()).isEqualTo(ObjectSelection(page, listOf(chip.id)))
            assertThat(chip.displayName).isEqualTo("Syllabus.pdf")
            assertThat(chip.sizeBytes).isEqualTo(bytes.size.toLong())
            assertThat(session.document.value.assets.keys).containsExactly(chip.asset)
            // Centered in the visible part of the page.
            val visible = session.viewport.visibleRectPt(page)
            assertThat(chip.bounds.center.x).isWithin(0.5f).of(visible.center.x)
            assertThat(chip.bounds.center.y).isWithin(0.5f).of(visible.center.y)

            val event = async(start = CoroutineStart.UNDISPATCHED) { vm.events.first() }
            vm.openAttachment(page, chip.id)
            val target = (event.await() as EditorEvent.OpenAttachment).target
            assertThat(target).isEqualTo(AttachmentTarget(chip.asset, "Syllabus.pdf", "application/pdf"))
            val cache = createTempDirectory("attach").toFile()
            val file = checkNotNull(vm.attachmentFile(target, cache))
            assertThat(file.name).isEqualTo("Syllabus.pdf")
            assertThat(file.parentFile?.parentFile?.name).isEqualTo(AttachmentFiles.CACHE_DIR)
            assertThat(file.readBytes()).isEqualTo(bytes)

            assertThat(session.undo()).isTrue()
            assertThat(session.document.value.attachments(page)).isEmpty()
            assertThat(session.document.value.assets).isEmpty()
            assertThat(session.redo()).isTrue()

            store.clear()
            awaitReleases()
            val reopened = viewModel(path).opened()
            reopened.documentSession.loadPages(listOf(page))
            assertThat(
                reopened.document.value
                    .attachments(page)
                    .single(),
            ).isEqualTo(chip)
            assertThat(reopened.documentSession.readAsset(chip.asset).getOrNull()).isEqualTo(bytes)
        }

    private fun Document.attachments(page: PageId) = pageBodies.getValue(page).objects.filterIsInstance<Attachment>()

    private fun Document.images(page: PageId) = pageBodies.getValue(page).objects.filterIsInstance<ImageObject>()

    private fun pngBytes(
        w: Int,
        h: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2563EB.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
