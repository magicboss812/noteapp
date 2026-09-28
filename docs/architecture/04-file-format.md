# 04 File format (.folio v1)
Normative. Goals: one self-contained file per document, openable with any ZIP tool (images and PDFs visible to anyone), compact ink, forward-compatible, platform-neutral for a future desktop client.

## Container layout
A `.folio` file is a ZIP archive:
```
mimetype                      STORED, first entry, content: application/vnd.folio+zip
manifest.json                 DEFLATED, human-readable metadata
pages/<pageId>.pb             DEFLATED, protobuf folio.v1.Page
flows/<flowId>.md             DEFLATED, UTF-8 Markdown text of the flow
flows/<flowId>.json           DEFLATED, flow style + settings
assets/<sha256>.<ext>         STORED, images (jpg/png/webp), PDFs, attachments, custom template images
thumbs/cover.webp             STORED, library preview (<= 512 px long side)
thumbs/<pageId>.webp          STORED, page panel previews (<= 256 px), optional, regenerable
search/text.txt               DEFLATED, plain text of title + all flows (index input)
```
Unknown entries are preserved on rewrite. Entry names are ASCII only. No absolute paths, no `..`.

## Manifest
```json
{
  "format": "folio",
  "formatVersion": 1,
  "id": "5f0c...uuid",
  "title": "Physics 11 - Kinematics",
  "createdMs": 1790000000000,
  "modifiedMs": 1790003600000,
  "app": { "name": "Folio", "version": "0.1.0" },
  "tags": ["physics", "school"],
  "favorite": false,
  "pages": [ { "id": "uuid", "kind": "fixed", "size": "A4", "orientation": "portrait",
               "widthPt": 595.2756, "heightPt": 841.8898, "template": "LINED",
               "pdf": null, "contentBounds": null } ],
  "flows": ["uuid"],
  "assets": [ { "id": "sha256hex", "path": "assets/sha256hex.png", "mime": "image/png", "bytes": 123456, "name": "photo.png" } ],
  "links": ["doc-uuid-1", "doc-uuid-2"],
  "textPreview": "first 300 characters of plain text",
  "defaults": { "size": "A4", "orientation": "portrait", "template": "LINED", "spacingPt": 20.126, "paperArgb": "#FFFFFFFF" }
}
```
The page list carries page summaries so the library and page stack never need to decode page protobufs to lay out.

## Protobuf schema
File: `core/format/src/main/proto/folio/v1/page.proto` (Wire, Kotlin output). proto3.
```proto
syntax = "proto3";
package folio.v1;
option java_package = "dev.folio.core.format.proto.v1";   // generated Kotlin package only; no wire effect

message Page {
  string id = 1;
  PageSpec spec = 2;
  Background background = 3;
  repeated PageObject objects = 4;     // index 0 = bottom of z-order
  Rect content_bounds = 5;             // infinite pages, cached
}
message PageSpec {
  oneof kind { Fixed fixed = 1; Infinite infinite = 2; Custom custom = 3; }
}
message Fixed { PaperSize size = 1; Orientation orientation = 2; }
message Infinite { oneof origin { Fixed origin_fixed = 1; Custom origin_custom = 2; } }
message Custom { float width_pt = 1; float height_pt = 2; }
enum PaperSize { PAPER_SIZE_UNSPECIFIED = 0; A3 = 1; A4 = 2; A5 = 3; }
enum Orientation { ORIENTATION_UNSPECIFIED = 0; PORTRAIT = 1; LANDSCAPE = 2; }

message Background { fixed32 paper_argb = 1; Template template = 2; PdfBackground pdf = 3; }
message Template {
  TemplateKind kind = 1; float spacing_pt = 2; fixed32 line_argb = 3;
  float margin_left_pt = 4; float margin_top_pt = 5; string custom_asset = 6; float custom_grid_pt = 7;
}
enum TemplateKind {
  TEMPLATE_KIND_UNSPECIFIED = 0; BLANK = 1; LINED = 2; GRID = 3; DOTTED = 4; CORNELL = 5;
  GRAPH_AXES = 6; MUSIC_STAFF = 7; PLANNER_DAILY = 8; PLANNER_WEEKLY = 9; CUSTOM = 10;
}
message PdfBackground { string asset = 1; uint32 page_index = 2; }

message PageObject {
  string id = 1;
  oneof kind {
    InkStroke stroke = 2; Shape shape = 3; FlowFrame frame = 4;
    Image image = 5; StickyNote sticky = 6; Attachment attachment = 7;
  }
}
message InkStroke { BrushSpec brush = 1; StrokeInputs inputs = 2; Rect bounds = 3; }
message BrushSpec { BrushKind kind = 1; fixed32 argb = 2; float size_pt = 3; uint32 version = 4; float pressure_gamma = 5; }
enum BrushKind { BRUSH_KIND_UNSPECIFIED = 0; BALLPOINT = 1; FOUNTAIN = 2; PENCIL = 3; MARKER = 4; HIGHLIGHTER = 5; }
message StrokeInputs {
  sint64 origin_x = 1;                 // first point, 1/64 pt units
  sint64 origin_y = 2;
  repeated sint32 dx = 3;              // deltas from previous point (first = 0)
  repeated sint32 dy = 4;
  repeated sint32 dt = 5;              // 0.1 ms units, first = 0
  repeated uint32 pressure = 6;        // 0..1023, empty if unavailable
  repeated uint32 tilt = 7;            // 0.1 deg (0..900), empty if unavailable
  repeated sint32 orientation = 8;     // 0.1 deg (-1800..1800), empty if unavailable
  InputTool tool = 9;
}
enum InputTool { INPUT_TOOL_UNSPECIFIED = 0; STYLUS = 1; ERASER_END = 2; SYNTHETIC = 3; }
message Shape { ShapeKind kind = 1; repeated Point points = 2; float rotation_deg = 3; StrokeStyle stroke = 4; fixed32 fill_argb = 5; bool has_fill = 6; Rect bounds = 7; }
enum ShapeKind { SHAPE_KIND_UNSPECIFIED = 0; LINE = 1; ARROW = 2; RECTANGLE = 3; ELLIPSE = 4; TRIANGLE = 5; POLYGON = 6; }
message StrokeStyle { fixed32 argb = 1; float width_pt = 2; bool dashed = 3; }
message FlowFrame { string flow_id = 1; Rect rect = 2; uint32 order = 3; FrameRole role = 4; bool auto_grow = 5; }
enum FrameRole { FRAME_ROLE_UNSPECIFIED = 0; BODY = 1; BOX = 2; }
message Image { string asset = 1; Rect rect = 2; float rotation_deg = 3; Rect crop = 4; }
message StickyNote { string flow_id = 1; Rect rect = 2; float rotation_deg = 3; fixed32 argb = 4; }
message Attachment { string asset = 1; string display_name = 2; string mime = 3; Point position = 4; }
message Rect { float left = 1; float top = 2; float right = 3; float bottom = 4; }
message Point { float x = 1; float y = 2; }
```
Decoding (`PageCodec`): missing required messages (spec, background, template, stroke inputs, rects), blank ids, duplicate object ids and stroke channels of different lengths are `Corrupt(entry)`; `*_UNSPECIFIED` or unknown enum values map to defaults (A4, portrait, BLANK, BALLPOINT, POLYGON, BOX, STYLUS); a `PageObject` with no known kind (newer writer) is skipped. `content_bounds` is written for infinite pages and recomputed on read.
Flow style file `flows/<flowId>.json`: `{"fontFamily":"Inter","sizeRatio":0.5,"paragraphGapLines":0,"align":"start","autoContinue":true}`.

## Stroke encoding
- Quantize x/y to 1/64 pt (0.0055 mm), store first point absolute (`origin_*`) and the rest as zigzag deltas. Time in 0.1 ms deltas.
- Optional channels are omitted entirely when the device does not report them (checked per stroke).
- Decoding produces `StrokeInputs` float arrays; the ink bridge builds `StrokeInputBatch` from them. Round trip error <= 1/128 pt.
- Budget: a typical handwritten stroke (100 inputs, pressure) is ~500-700 bytes before DEFLATE.

## Assets
- Name = lowercase hex SHA-256 of the bytes + extension from the mime type. Identical bytes are stored once per document.
- Images: JPEG and PNG stored as-is; HEIC/WebP/other converted to PNG (or JPEG q92 for photos > 4 MP) on import so any viewer can open them.
- PDFs: stored as-is (the original file). Attachments: stored as-is with the original name in the manifest.
- Unreferenced assets are removed at pack time only when no undo entry references them.

## Versioning
- `formatVersion` in the manifest = major version (integer). v1 now.
- Readers accept `formatVersion <= supported` and run migrations in sequence (`Migration(from, to)` registry in core:format). Newer versions: typed error `FormatTooNew`, open impossible, library shows a badge.
- Additive changes within a major version: new optional JSON keys (ignored by older readers), new protobuf fields with new numbers. Removing or reinterpreting fields requires a major bump.
- Every version change adds golden files under `testdata/format/v<N>/`.

## Write protocol
1. Editing happens in the working copy `files/work/<docId>/` (same layout as the ZIP, unpacked) plus `base.json` (source path, source size + mtime, manifest modifiedMs at open, dirty entry names, lastPackMs).
2. Entry autosave: changed pages/flows/manifest are written after 1 s idle as `<entry>.tmp` -> fsync -> rename.
3. Pack triggers: editor close, app `onStop`, every 30 s while dirty, before export and share.
4. Pack: write `<name>.folio.tmp` in the target directory from the working copy (entry order as in "Container layout"), flush + fsync, rename over `<name>.folio`, fsync directory where supported. Then copy the previous file version to `files/backup/<docId>.folio` (one generation) and update `base.json`.
5. Packing never blocks the main thread. Pack of a 50 MB document <= 1.5 s (assets STORED, no recompression).

## Crash recovery
- On app start, scan `files/work/*/base.json`. A working copy with dirty entries or newer than its source is packed (unless the source changed externally, see Conflicts). The UI shows a one-time "Recovered unsaved changes" snackbar.
- Stale `.folio.tmp` files in the library are deleted on start if older than 10 minutes.
- Working copies of documents that no longer exist (deleted externally) are kept for 7 days and offered as "Recovered: <title>" in the library.

## Conflicts
- Before packing, compare the source file's size + mtime with `base.json`. If the source changed externally (another app, USB copy) and the working copy is dirty: pack to `<title> (conflict YYYY-MM-DD HH-mm).folio` in the same folder, keep the external version untouched, notify the user.
- If the source changed externally and the working copy is clean: reload from the source on next open/resume.
