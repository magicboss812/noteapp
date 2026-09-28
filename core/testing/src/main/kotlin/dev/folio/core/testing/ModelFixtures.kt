// Test data ranges (sizes, counts, coordinates) are the point of this file; naming each would hide them.
@file:Suppress("MagicNumber")

package dev.folio.core.testing

import dev.folio.core.model.AssetId
import dev.folio.core.model.AssetInfo
import dev.folio.core.model.Attachment
import dev.folio.core.model.Background
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.DocId
import dev.folio.core.model.Document
import dev.folio.core.model.DocumentMeta
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Orientation
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.RectF01
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StickyNote
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.Template
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.TextAlign
import dev.folio.core.model.TextFlow
import dev.folio.core.model.edit.shapeBounds
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlin.random.Random

/** Builders for model objects in tests. Random variants are deterministic for a given [Random] seed. */
object ModelFixtures {
    /** College-ruled A4 background (7.1 mm rules). */
    val LINED_BACKGROUND =
        Background(
            paperArgb = 0xFFFFFFFF.toInt(),
            template = Template(TemplateKind.LINED, 20.126f, 0xFFC9D3E0.toInt(), 70.866f, 70.866f, null, null),
            pdf = null,
        )

    /** A4 portrait. */
    val A4 = PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT)

    /** Default flow style (Inter, M). */
    val FLOW_STYLE = FlowStyle("Inter", 0.45f, 0, TextAlign.START)

    /** Empty page with a deterministic id. */
    fun page(
        id: String,
        objects: List<PageObject> = emptyList(),
        spec: PageSpec = A4,
    ): Page = Page(PageId(id), spec, LINED_BACKGROUND, objects.toPersistentList())

    /** Document with all [pages] loaded. */
    fun document(
        pages: List<Page>,
        flows: List<TextFlow> = emptyList(),
        assets: List<AssetInfo> = emptyList(),
        id: String = "00000000-0000-4000-8000-000000000001",
    ): Document =
        Document(
            meta =
                DocumentMeta(
                    id = DocId(id),
                    title = "Test",
                    createdMs = 1_767_225_600_000L,
                    modifiedMs = 1_767_225_600_000L,
                    tags = persistentSetOf(),
                    favorite = false,
                    formatVersion = 1,
                    defaultPageSpec = A4,
                    defaultBackground = LINED_BACKGROUND,
                ),
            pages = pages.map { it.toRef() }.toPersistentList(),
            flows = flows.associateBy { it.id }.toPersistentMap(),
            assets = assets.associateBy { it.id }.toPersistentMap(),
            pageBodies = pages.associateBy { it.id }.toPersistentMap(),
        )

    /** Text flow with [markdown]. */
    fun flow(
        id: String,
        markdown: String = "",
    ): TextFlow = TextFlow(FlowId(id), markdown, FLOW_STYLE, autoContinue = true)

    /** Random stroke of [points] samples inside a 500 pt square, all channels present unless [minimal]. */
    fun randomStroke(
        random: Random,
        id: ObjectId = ObjectId(randomId(random)),
        points: Int = 1 + random.nextInt(60),
        minimal: Boolean = false,
    ): InkStroke {
        val x = FloatArray(points)
        val y = FloatArray(points)
        val t = FloatArray(points)
        var cx = random.nextFloat() * 500f
        var cy = random.nextFloat() * 500f
        for (i in 0 until points) {
            cx += random.nextFloat() * 6f - 3f
            cy += random.nextFloat() * 6f - 3f
            x[i] = cx
            y[i] = cy
            t[i] = if (i == 0) 0f else t[i - 1] + 2.2f + random.nextFloat()
        }
        val inputs =
            StrokeInputs(
                x = x,
                y = y,
                tMs = t,
                pressure = if (minimal) null else FloatArray(points) { random.nextFloat() },
                tiltDeg = if (minimal) null else FloatArray(points) { random.nextFloat() * 90f },
                orientationDeg = if (minimal) null else FloatArray(points) { random.nextFloat() * 360f - 180f },
                tool = if (minimal) InputTool.SYNTHETIC else InputTool.STYLUS,
            )
        val kind = BrushKind.entries[random.nextInt(BrushKind.entries.size)]
        return InkStroke.of(id, BrushSpec(kind, random.nextInt() or OPAQUE, 0.5f + random.nextFloat() * 4f, 1), inputs)
    }

    /** Random object of any variant. */
    fun randomObject(random: Random): PageObject {
        val id = ObjectId(randomId(random))
        val rect =
            RectPt.ofSize(
                random.nextFloat() * 400f,
                random.nextFloat() * 600f,
                10f + random.nextFloat() * 150f,
                10f + random.nextFloat() * 150f,
            )
        val rotation = if (random.nextBoolean()) 0f else random.nextFloat() * 360f - 180f
        return when (random.nextInt(VARIANTS)) {
            0 -> {
                randomStroke(random, id)
            }

            1 -> {
                val points = List(2 + random.nextInt(4)) { PointPt(random.nextFloat() * 500f, random.nextFloat() * 700f) }
                val style = StrokeStyle(random.nextInt() or OPAQUE, 1f + random.nextFloat() * 3f, random.nextBoolean())
                val kind = ShapeKind.entries[random.nextInt(ShapeKind.entries.size)]
                val fill = if (random.nextBoolean()) random.nextInt() else null
                Shape(id, kind, points, rotation, style, fill, shapeBounds(points, rotation, style.widthPt))
            }

            2 -> {
                FlowFrame(id, FlowId(randomId(random)), rect, random.nextInt(3), FrameRole.entries[random.nextInt(2)], random.nextBoolean())
            }

            3 -> {
                ImageObject(id, AssetId(hex64(random)), rect, rotation, RectF01(0f, 0.1f, 0.9f, 1f))
            }

            4 -> {
                StickyNote(id, FlowId(randomId(random)), rect, rotation, random.nextInt() or OPAQUE)
            }

            else -> {
                Attachment(id, AssetId(hex64(random)), "file ${random.nextInt(100)}.pdf", "application/pdf", PointPt(rect.left, rect.top))
            }
        }
    }

    /** Random page with [objectCount] objects. */
    fun randomPage(
        random: Random,
        objectCount: Int,
        spec: PageSpec = A4,
    ): Page = Page(PageId(randomId(random)), spec, LINED_BACKGROUND, List(objectCount) { randomObject(random) }.toPersistentList())

    /** Deterministic UUIDv4-shaped id. */
    fun randomId(random: Random): String {
        val hex = hex64(random)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-4${hex.substring(13, 16)}-a${hex.substring(17, 20)}-${hex.substring(20, 32)}"
    }

    /** 64 lowercase hex characters (asset-id shaped). */
    fun hex64(random: Random): String = buildString { repeat(64) { append(HEX[random.nextInt(16)]) } }

    private const val OPAQUE = 0xFF000000.toInt()
    private const val VARIANTS = 6
    private const val HEX = "0123456789abcdef"
}
