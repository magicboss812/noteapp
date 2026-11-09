// Fixture geometry (positions, sizes, colors) is the point of this file; naming each would hide it.
@file:Suppress("MagicNumber")

package dev.folio.core.storage.session

import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.common.Outcome
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.AssetId
import dev.folio.core.model.AssetInfo
import dev.folio.core.model.Attachment
import dev.folio.core.model.Document
import dev.folio.core.model.FlowBox
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.RectF01
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StickyNote
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.EditFlow
import dev.folio.core.model.edit.MovePages
import dev.folio.core.model.edit.ObjectCopies
import dev.folio.core.model.edit.PageOps
import dev.folio.core.model.edit.RecolorObjects
import dev.folio.core.model.edit.RemoveObjects
import dev.folio.core.model.edit.ReorderObjects
import dev.folio.core.model.edit.ReorderOp
import dev.folio.core.model.edit.ReplaceObjects
import dev.folio.core.model.edit.SelectionEdit
import dev.folio.core.model.edit.TextEdit
import dev.folio.core.model.edit.TransformObjects
import dev.folio.core.model.edit.UpdateAssets
import dev.folio.core.model.edit.UpdateBackground
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.model.edit.UpdateMeta
import dev.folio.core.model.edit.UpdateObjects
import dev.folio.core.model.edit.UpdatePageSpec
import dev.folio.core.model.edit.command
import dev.folio.core.model.edit.ownsFlow
import dev.folio.core.model.edit.shapeBounds
import dev.folio.core.model.edit.transformed
import dev.folio.core.model.edit.withId
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.storage.repo.RepositoryTestBase
import dev.folio.core.storage.work.PackResult
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopyStore
import dev.folio.core.testing.ModelAssertions.assertPageEquivalent
import dev.folio.core.testing.ModelFixtures
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import kotlin.random.Random

/**
 * R-FILE-05 coverage (P07-T08): every object type and operation, and the page and document operations, run through a
 * real session: execute, undo (base state back), redo (edited state back), pack on close, reopen from the packed file
 * (working copy discarded), compare. The base note holds one object of each type on its first page.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class UndoPersistenceMatrixTest(
    private val case: Case,
) : RepositoryTestBase() {
    private val appFs by lazy { fs.sub(".app") }

    @Test
    fun executeUndoRedoPackReopen_matchesAtEveryStep() =
        runTest {
            val ref = documents.create(spec("Matrix", pages = 2)).orThrow()
            val session = sessions(backgroundScope).open(ref.path).orThrow()
            val fixture = Fixture.setUp(session)
            val base = session.loaded()

            session.execute(case.command(fixture)).orThrow()
            val edited = session.loaded()
            assertWithMessage("$case changes the note").that(edited.comparable()).isNotEqualTo(base.comparable())

            session.undo().orThrow()
            assertWithMessage("$case undo").that(session.loaded().comparable()).isEqualTo(base.comparable())
            session.redo().orThrow()
            assertWithMessage("$case redo").that(session.loaded().comparable()).isEqualTo(edited.comparable())

            assertWithMessage("$case pack").that(sessions(backgroundScope).close(session).orThrow()).isEqualTo(PackResult.Packed(ref.path))
            WorkingCopyStore(appFs, fs).discard(ref.docId).orThrow()
            val reopened = sessions(backgroundScope).open(ref.path).orThrow()
            val read = reopened.loaded()
            assertEquivalent(edited, read)
            read.assets.keys.forEach { id ->
                assertWithMessage(
                    "$case asset ${id.value} bytes",
                ).that(reopened.readAsset(id).orThrow()).isEqualTo(fixture.bytes.getValue(id))
            }
        }

    private fun sessions(scope: CoroutineScope) =
        DocumentSessions(
            WorkingCopyStore(appFs, fs),
            Packer(fs, appFs, clock),
            scanner,
            clock,
            dispatchers,
            scope,
            ManifestApp("Folio", "test"),
        )

    private suspend fun DocumentSession.loaded(): Document {
        loadPages(document.value.pages.map { it.id }).orThrow()
        return document.value
    }

    // In memory nothing is quantized, so undo and redo must give back the exact state (the save time aside).
    private fun Document.comparable(): List<Any> = listOf(meta.copy(modifiedMs = 0), pages, flows, assets, pageBodies)

    private fun assertEquivalent(
        expected: Document,
        actual: Document,
    ) {
        assertWithMessage("$case reopened meta").that(actual.meta.copy(modifiedMs = 0)).isEqualTo(expected.meta.copy(modifiedMs = 0))
        assertWithMessage("$case reopened pages").that(actual.pages).isEqualTo(expected.pages)
        assertWithMessage("$case reopened flows").that(actual.flows).isEqualTo(expected.flows)
        assertWithMessage("$case reopened assets").that(actual.assets).isEqualTo(expected.assets)
        expected.pages.forEach {
            assertPageEquivalent(expected.pageBodies.getValue(it.id), actual.pageBodies.getValue(it.id), "$case reopened")
        }
    }

    /** One row of the matrix: a name and the command it runs on the base note. */
    class Case(
        private val name: String,
        val command: (Fixture) -> EditCommand,
    ) {
        override fun toString(): String = name
    }

    /** The base note: pages [first] (one object of each [Kind]) and [second] (empty), flows and assets for them. */
    class Fixture(
        val doc: Document,
        val first: PageId,
        val second: PageId,
        val objects: Map<Kind, PageObject>,
        val bytes: Map<AssetId, ByteArray>,
        val extraImage: AssetInfo,
    ) {
        /** Id of the [kind] object on [first]. */
        fun id(kind: Kind): ObjectId = objects.getValue(kind).id

        /** Fixture builder. */
        companion object {
            /** Stores the assets and adds the objects, flows and assets in one step (not part of the case's undo). */
            suspend fun setUp(session: DocumentSession): Fixture {
                val (first, second) =
                    session.document.value.pages
                        .map { it.id }
                session.loadPages(listOf(first, second)).must()
                val png = ByteArray(3000) { (it * 7).toByte() }
                val pdf = ByteArray(2000) { (it * 13).toByte() }
                val extra = ByteArray(1500) { (it * 3).toByte() }
                val image = session.storeAsset(png, "image/png", "photo.png").must()
                val file = session.storeAsset(pdf, "application/pdf", "report.pdf").must()
                val extraImage = session.storeAsset(extra, "image/png", "second.png").must()
                val boxFlow = ModelFixtures.flow("box-flow", "# Box\n\ntext").copy(box = FlowBox.created(120f))
                val stickyFlow = ModelFixtures.flow("sticky-flow", "sticky *text*")
                val objects =
                    mapOf(
                        Kind.STROKE to ModelFixtures.randomStroke(Random(808), ObjectId("stroke"), points = 40),
                        Kind.SHAPE to shape(ObjectId("shape")),
                        Kind.TEXT_BOX to FlowFrame(ObjectId("box"), boxFlow.id, RectPt(100f, 300f, 400f, 380f), 0, FrameRole.BOX, true),
                        Kind.IMAGE to
                            ImageObject(ObjectId("image"), image.id, RectPt(300f, 500f, 450f, 600f), 10f, RectF01(0f, 0.1f, 0.9f, 1f)),
                        Kind.STICKY to
                            StickyNote(ObjectId("sticky"), stickyFlow.id, RectPt(60f, 600f, 276f, 816f), -2f, StickyNote.COLORS[0]),
                        Kind.ATTACHMENT to
                            Attachment(
                                ObjectId("attachment"),
                                file.id,
                                "report.pdf",
                                "application/pdf",
                                PointPt(320f, 700f),
                                pdf.size.toLong(),
                            ),
                    )
                session
                    .execute(
                        Batch(
                            listOf(
                                UpdateAssets(listOf(image, file)),
                                UpdateFlows(listOf(boxFlow, stickyFlow)),
                                AddObjects(first, Kind.entries.map { objects.getValue(it) }),
                            ),
                        ),
                    ).must()
                val bytes = mapOf(image.id to png, file.id to pdf, extraImage.id to extra)
                return Fixture(session.document.value, first, second, objects, bytes, extraImage)
            }

            private fun <T> Outcome<T>.must(): T =
                when (this) {
                    is Outcome.Success -> value
                    is Outcome.Failure -> throw AssertionError(message, cause)
                }

            private fun shape(id: ObjectId): Shape {
                val points = listOf(PointPt(80f, 120f), PointPt(260f, 220f))
                val style = StrokeStyle(0xFF2563EB.toInt(), 2f, dashed = false)
                return Shape(id, ShapeKind.RECTANGLE, points, 0f, style, null, shapeBounds(points, 0f, style.widthPt))
            }
        }
    }

    /** Every object type. */
    enum class Kind { STROKE, SHAPE, TEXT_BOX, IMAGE, STICKY, ATTACHMENT }

    /** Every object operation; [appliesTo] leaves out pairs the editor never produces (recoloring an image). */
    enum class Op(
        val appliesTo: (Kind) -> Boolean = { true },
    ) {
        ADD,
        DELETE,
        MOVE,
        SCALE_ROTATE,
        RECOLOR({ it == Kind.STROKE || it == Kind.SHAPE || it == Kind.STICKY }),

        // The base page stacks the kinds in declaration order: the top one cannot come forward, the bottom one not go back.
        BRING_TO_FRONT({ it != Kind.entries.last() }),
        SEND_TO_BACK({ it != Kind.entries.first() }),
        EDIT_PROPERTIES,
        REPLACE,
        DUPLICATE,
        CUT_PASTE_TO_OTHER_PAGE,
    }

    /** The matrix rows. */
    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = (objectCases() + pageCases() + documentCases()).map { arrayOf(it) }

        private fun objectCases(): List<Case> =
            Kind.entries.flatMap { kind ->
                Op.entries.filter { it.appliesTo(kind) }.map { op ->
                    Case("${kind.name.lowercase()} ${op.name.lowercase()}") { objectCommand(it, kind, op) }
                }
            }

        private fun objectCommand(
            f: Fixture,
            kind: Kind,
            op: Op,
        ): EditCommand {
            val obj = f.objects.getValue(kind)
            val ids = listOf(obj.id)
            return when (op) {
                Op.ADD -> {
                    withOwnFlow(
                        f,
                        obj.withId(ObjectId("added")).moved(Affine.translate(0f, 40f)),
                    ) { AddObjects(f.second, listOf(it)) }
                }

                Op.DELETE -> {
                    SelectionEdit.Delete.command(f.first, ids)
                }

                Op.MOVE -> {
                    SelectionEdit.Transform(Affine.translate(37.5f, -21.25f)).command(f.first, ids)
                }

                Op.SCALE_ROTATE -> {
                    TransformObjects(
                        f.first,
                        ids,
                        Affine.rotate(30f, 250f, 400f).after(Affine.scale(1.5f, 0.75f, 250f, 400f)),
                    )
                }

                Op.RECOLOR -> {
                    RecolorObjects(f.first, ids, 0xFFDC2626.toInt())
                }

                Op.BRING_TO_FRONT -> {
                    ReorderObjects(f.first, ids, ReorderOp.BRING_TO_FRONT)
                }

                Op.SEND_TO_BACK -> {
                    SelectionEdit.Reorder(ReorderOp.SEND_TO_BACK).command(f.first, ids)
                }

                Op.EDIT_PROPERTIES -> {
                    edited(f, obj)
                }

                Op.REPLACE -> {
                    withOwnFlow(f, obj.withId(ObjectId("replacement")).moved(Affine.translate(5f, 5f))) {
                        ReplaceObjects(f.first, ids, listOf(it))
                    }
                }

                Op.DUPLICATE -> {
                    duplicate(f, obj)
                }

                Op.CUT_PASTE_TO_OTHER_PAGE -> {
                    Batch(listOf(RemoveObjects(f.first, ids), AddObjects(f.second, listOf(obj))))
                }
            }
        }

        // Objects with text of their own get a copy of their flow, as the page duplicate does.
        private fun withOwnFlow(
            f: Fixture,
            obj: PageObject,
            command: (PageObject) -> EditCommand,
        ): EditCommand {
            val flowId =
                when (obj) {
                    is FlowFrame -> obj.flow
                    is StickyNote -> obj.flow
                    else -> return command(obj)
                }
            val clone =
                f.doc.flows
                    .getValue(flowId)
                    .copy(id = FlowId("${flowId.value}-copy"))
            val withClone =
                when (obj) {
                    is FlowFrame -> obj.copy(flow = clone.id)
                    is StickyNote -> obj.copy(flow = clone.id)
                    else -> obj
                }
            return Batch(listOf(UpdateFlows(listOf(clone)), command(withClone)))
        }

        private fun duplicate(
            f: Fixture,
            obj: PageObject,
        ): EditCommand {
            if (obj.ownsFlow) return withOwnFlow(f, obj.withId(ObjectId("duplicate")).moved(offset())) { AddObjects(f.first, listOf(it)) }
            var n = 0
            val copies = ObjectCopies.of(listOf(obj), offset()) { ObjectId("duplicate-${n++}") }
            return SelectionEdit.Add(copies).command(f.first, emptyList())
        }

        private fun offset() = Affine.translate(ObjectCopies.DUPLICATE_OFFSET_PT, ObjectCopies.DUPLICATE_OFFSET_PT)

        private fun PageObject.moved(affine: Affine): PageObject = transformed(affine)

        // The property edits the editor offers per type: pen size, shape fill and dash, box width, crop and image swap,
        // sticky tilt and its text, attachment name.
        private fun edited(
            f: Fixture,
            obj: PageObject,
        ): EditCommand =
            when (obj) {
                is InkStroke -> {
                    UpdateObjects(f.first, listOf(InkStroke.of(obj.id, obj.brush.copy(sizePt = obj.brush.sizePt * 2f), obj.inputs)))
                }

                is Shape -> {
                    UpdateObjects(f.first, listOf(obj.copy(fillArgb = 0x8034D399.toInt(), stroke = obj.stroke.copy(dashed = true))))
                }

                is FlowFrame -> {
                    val flow = f.doc.flows.getValue(obj.flow)
                    Batch(
                        listOf(
                            UpdateObjects(f.first, listOf(obj.copy(rect = obj.rect.copy(right = obj.rect.right + 60f), autoGrow = false))),
                            UpdateFlows(listOf(flow.copy(box = flow.box.copy(autoWidth = false, offsetPt = 6f)))),
                        ),
                    )
                }

                is ImageObject -> {
                    Batch(
                        listOf(
                            UpdateAssets(listOf(f.extraImage)),
                            UpdateObjects(
                                f.first,
                                listOf(obj.copy(asset = f.extraImage.id, crop = RectF01(0.2f, 0f, 1f, 0.75f), rotationDeg = 100f)),
                            ),
                        ),
                    )
                }

                is StickyNote -> {
                    Batch(
                        listOf(
                            UpdateObjects(f.first, listOf(obj.copy(rotationDeg = 3.5f, argb = StickyNote.COLORS[2]))),
                            EditFlow(obj.flow, listOf(TextEdit(0, 6, "Note"))),
                        ),
                    )
                }

                is Attachment -> {
                    UpdateObjects(f.first, listOf(obj.copy(displayName = "renamed.pdf")))
                }
            }

        private fun pageCases(): List<Case> =
            listOf(
                Case("page insert blank") { f ->
                    PageOps.blankNear(
                        f.doc,
                        f.first,
                        PageOps.Side.BEFORE,
                        ModelFixtures.LINED_BACKGROUND,
                    ) { PageId("10000000-0000-4000-8000-000000000001") }
                },
                Case("page delete with objects") { f -> checkNotNull(PageOps.delete(f.doc, listOf(f.first))) },
                Case("page move") { f -> MovePages(listOf(f.first), 1) },
                Case("page duplicate with text objects") { f ->
                    var n = 0
                    PageOps
                        .duplicate(
                            f.doc,
                            listOf(f.first),
                            newPageId = { PageId("20000000-0000-4000-8000-000000000001") },
                            newObjectId = { ObjectId("copy-${n++}") },
                            newFlowId = { FlowId("flow-copy-${n++}") },
                        ).command
                },
                Case("page spec landscape") { f -> UpdatePageSpec(f.first, PageSpec.Fixed(PaperSize.A4, Orientation.LANDSCAPE)) },
                Case("page background grid") { f ->
                    val bg = ModelFixtures.LINED_BACKGROUND
                    UpdateBackground(
                        f.first,
                        bg.copy(paperArgb = 0xFFFFF8E7.toInt(), template = bg.template.copy(kind = TemplateKind.GRID)),
                    )
                },
                Case("page restyle both pages") { f ->
                    checkNotNull(
                        PageOps.restyle(
                            f.doc,
                            listOf(f.first, f.second),
                            spec = { PageSpec.Fixed(PaperSize.A4, Orientation.LANDSCAPE) },
                            background = { it.background.copy(paperArgb = 0xFF1F2937.toInt()) },
                        ),
                    )
                },
            )

        private fun documentCases(): List<Case> =
            listOf(
                Case("meta title tags favorite") { f ->
                    UpdateMeta(f.doc.meta.copy(title = "Renamed", tags = persistentSetOf("math", "week 3"), favorite = true))
                },
                Case(
                    "flow text edit",
                ) { f ->
                    EditFlow(
                        FlowId("box-flow"),
                        listOf(TextEdit(2, 5, "Heading"), TextEdit(0, 0, "x ")),
                        base =
                            f.doc.flows
                                .getValue(FlowId("box-flow"))
                                .markdown,
                    )
                },
                Case("flow style") { f ->
                    val flow = f.doc.flows.getValue(FlowId("box-flow"))
                    UpdateFlows(
                        listOf(
                            flow.copy(style = flow.style.copy(sizeRatio = 0.6f, textArgb = 0xFFDC2626.toInt(), baselineLiftUnits = 0.2f)),
                        ),
                    )
                },
                Case("asset add unreferenced") { f -> UpdateAssets(listOf(f.extraImage)) },
                Case(
                    "lasso multi-object move",
                ) { f -> TransformObjects(f.first, Kind.entries.map { f.id(it) }, Affine.translate(-12f, 48f)) },
                Case(
                    "lasso multi-object delete",
                ) { f -> RemoveObjects(f.first, listOf(f.id(Kind.SHAPE), f.id(Kind.IMAGE), f.id(Kind.STICKY))) },
            )
    }
}
