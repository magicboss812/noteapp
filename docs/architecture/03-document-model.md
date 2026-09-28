# 03 Document model
Pure Kotlin in `core:model` (JVM module). Immutable data; persistent collections from kotlinx.collections.immutable.

## Ids and units
- Ids: `DocId`, `PageId`, `ObjectId`, `FlowId`, `AssetId` are value classes over strings. Doc/page/object/flow ids are UUIDv4 (lowercase, no braces); `AssetId` is the lowercase hex SHA-256 of the asset bytes.
- Page space unit: PDF point (1 pt = 1/72 inch = 0.352778 mm). Origin top-left of the page, y down. Infinite pages allow negative coordinates.
- Colors: ARGB `Int` (`0xAARRGGBB`), sRGB.
- Angles: degrees. Time: epoch ms for metadata, stroke timing relative per stroke.
- Screen units (px, dp) never appear in core:model.

## Geometry
- `PointPt(x: Float, y: Float)`, `RectPt(left, top, right, bottom)` with `union`, `intersects`, `contains`, `inset`, `isEmpty`.
- `Affine(a, b, c, d, tx, ty)` with `compose`, `invert`, `mapPoint`, `mapRect` (axis-aligned bounds of the mapped rect).
- `polygonContains(points, p)` (even-odd), `segmentDistance(p, a, b)`, `polylineLength`.
- `UniformGridIndex<T>` per page: cell 128 pt; `insert(id, bounds)`, `remove(id)`, `update(id, bounds)`, `query(rect)`, `queryRadius(point, r)`. Used for hit testing, lasso candidates, and tile invalidation.

## Document
```kotlin
data class Document(
  val meta: DocumentMeta,
  val pages: PersistentList<PageRef>,          // order = page order; Page bodies load lazily
  val flows: PersistentMap<FlowId, TextFlow>,
  val assets: PersistentMap<AssetId, AssetInfo>,
  val pageBodies: PersistentMap<PageId, Page>,  // decoded bodies the session holds (A-009)
)
data class DocumentMeta(
  val id: DocId, val title: String, val createdMs: Long, val modifiedMs: Long,
  val tags: PersistentSet<String>, val favorite: Boolean, val formatVersion: Int,
  val defaultPageSpec: PageSpec, val defaultBackground: Background,
)
data class AssetInfo(val id: AssetId, val mime: String, val bytes: Long, val originalName: String?)
```
`PageRef(id, spec, background, contentBounds?)` = lightweight summary so the page stack can lay out all pages without decoding objects. `Page` is decoded on demand by the session and kept in `pageBodies`; a page without a body is unchanged since its last write. Commands that touch objects require the body to be loaded; for a loaded page, body spec/background equal the `PageRef` (A-009).

## Pages
```kotlin
enum class PaperSize(val widthPt: Float, val heightPt: Float) {
  A3(841.8898f, 1190.5512f), A4(595.2756f, 841.8898f), A5(419.5276f, 595.2756f)
}
enum class Orientation { PORTRAIT, LANDSCAPE }             // landscape swaps width/height
sealed interface PageSpec {
  data class Fixed(val size: PaperSize, val orientation: Orientation) : PageSpec
  data class Custom(val widthPt: Float, val heightPt: Float) : PageSpec   // PDF pages with other sizes
  data class Infinite(val origin: PageSpec) : PageSpec     // origin is Fixed or Custom; keeps the template frame
}
data class Background(val paperArgb: Int, val template: Template, val pdf: PdfBackground?)
data class Template(val kind: TemplateKind, val spacingPt: Float, val lineArgb: Int,
                    val marginLeftPt: Float, val marginTopPt: Float,
                    val customAsset: AssetId?, val customGridPt: Float?)
data class PdfBackground(val asset: AssetId, val pageIndex: Int)  // page spec = PDF page size (A-size match or Custom)
data class Page(val id: PageId, val spec: PageSpec, val background: Background,
                val objects: PersistentList<PageObject>)          // index 0 = bottom of z-order
```
PDF pages whose size matches A3/A4/A5 within 1 pt use `Fixed`, all others `Custom`. Template kinds: see 05-canvas-rendering.md#templates.
Content bounds of infinite pages = union of object bounds, cached in the page summary.

## Objects
```kotlin
sealed interface PageObject { val id: ObjectId; val bounds: RectPt }
data class InkStroke(override val id: ObjectId, val brush: BrushSpec, val inputs: StrokeInputs,
                     override val bounds: RectPt) : PageObject
data class BrushSpec(val kind: BrushKind, val argb: Int, val sizePt: Float, val version: Int,
                     val pressureGamma: Float = 1f)
enum class BrushKind { BALLPOINT, FOUNTAIN, PENCIL, MARKER, HIGHLIGHTER }
class StrokeInputs(               // struct-of-arrays, immutable after creation
  val x: FloatArray, val y: FloatArray, val tMs: FloatArray,
  val pressure: FloatArray?, val tiltDeg: FloatArray?, val orientationDeg: FloatArray?,
  val tool: InputTool)            // STYLUS, ERASER_END, SYNTHETIC
data class Shape(override val id: ObjectId, val kind: ShapeKind, val points: List<PointPt>,
                 val rotationDeg: Float, val stroke: StrokeStyle, val fillArgb: Int?,
                 override val bounds: RectPt) : PageObject
enum class ShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE, TRIANGLE, POLYGON }
data class StrokeStyle(val argb: Int, val widthPt: Float, val dashed: Boolean)
data class FlowFrame(override val id: ObjectId, val flow: FlowId, val rect: RectPt, val order: Int,
                     val role: FrameRole, val autoGrow: Boolean) : PageObject   // BODY, BOX
data class ImageObject(override val id: ObjectId, val asset: AssetId, val rect: RectPt,
                       val rotationDeg: Float, val crop: RectF01) : PageObject   // crop normalized 0..1
data class StickyNote(override val id: ObjectId, val flow: FlowId, val rect: RectPt,
                      val rotationDeg: Float, val argb: Int) : PageObject
data class Attachment(override val id: ObjectId, val asset: AssetId, val displayName: String,
                      val mime: String, val position: PointPt) : PageObject
data class TextFlow(val id: FlowId, val markdown: String, val style: FlowStyle, val autoContinue: Boolean)
data class FlowStyle(val fontFamily: String, val sizeRatio: Float, val paragraphGapLines: Int,
                     val align: TextAlign)
```
Z-order rules: new objects go on top. Body flow frames are created at the bottom of the z-order so ink drawn later lies above text. Highlighter strokes are ordinary strokes drawn with transparency.
Tables, links, math, checklists live inside flow Markdown, not as page objects.

## Commands and undo
- `EditCommand.execute(doc: Document): Applied` where `Applied(doc, inverse: EditCommand, coalesceKey: String?)`. The inverse is computed from the pre-state at execution time.
- Commands: `AddObjects(pageId, objects, atIndex?)`, `RemoveObjects(pageId, ids)`, `ReplaceObjects(pageId, removed, added)` (eraser split, shape recognition), `TransformObjects(pageId, ids, affine)`, `RecolorObjects`, `ReorderObjects(pageId, ids, op)`, `EditFlow(flowId, edits)` (block-level text edits), `InsertPages`, `RemovePages`, `MovePages`, `UpdatePageSpec`, `UpdateBackground`, `UpdateMeta`, `AddAsset`, `Batch(list)`.
- `UndoManager(capacity = 50)`: stack of `Applied` entries; `push`, `undo`, `redo`; redo stack cleared by push; entries with the same `coalesceKey` within 1000 ms merge (typing in one block, continuous slider drags). One pen/eraser/lasso gesture = one entry.
- Assets referenced by no object after undo history expiry are dropped at pack time (never while an undo entry may still need them).

## Sessions
- `DocumentSession` (core:storage) owns: working copy, `StateFlow<Document>`, decoded page cache (LRU 30 pages; pages with pending edits are pinned), UndoManager, dirty set, autosave scheduler, pack triggers.
- Page decode happens on the io dispatcher; UI requests pages by visible range + prefetch 2 ahead/behind.
- Lifecycle: `open(path)` -> ready -> `execute/undo/redo` -> `close()` (final pack). App `onStop` packs all open sessions. Process death is covered by working copy recovery.
- Two panes may show the same document only read-only in the second pane (v1 rule; avoids concurrent sessions on one file).
