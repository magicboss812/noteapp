# P02 Foundation
Goal: pure model, undoable commands, the `.folio` format, crash-safe persistence, library index, document sessions. Mostly JVM code with strong tests.
Exit: round-trip, crash-simulation, and index tests green; tag `p02-done`.

### P02-T01 Model types and geometry
Implements: R-CORE-02, R-FILE-02
Read: 03-document-model.md#ids-and-units, #geometry, #document, #pages, #objects
Files: core/model/src/main/kotlin/dev/folio/core/model/**
Do:
1. Types exactly as in 03-document-model.md (ids, PaperSize, PageSpec, Background, Template, PageObject variants, BrushSpec, StrokeInputs, TextFlow, FlowStyle).
2. Geometry: PointPt, RectPt, Affine (compose, invert, mapRect), polygon contains, segment/rect distance.
3. `UniformGridIndex` (cell 128 pt): insert, remove, update, query(rect), queryRadius(point, r).
Accept:
- [ ] unit: PaperSize dimensions (A4 = 595.2756 x 841.8898 pt, A5, A3; landscape swaps)
- [ ] unit: Affine compose/invert round trip within 1e-4
- [ ] unit: index queries equal brute force on 10k random rects (fixed seed)
Verify: `./gradlew :core:model:test`

### P02-T02 Commands and undo
Implements: R-FILE-05
Read: 03-document-model.md#commands-and-undo
Files: core/model/.../edit/**
Do:
1. `EditCommand` sealed interface; executing returns `Applied(newDoc, inverse, coalesceKey?)`.
2. Commands: AddObjects, RemoveObjects, ReplaceObjects, TransformObjects, ReorderObjects, EditFlow (block edits), InsertPages, RemovePages, MovePages, UpdatePageSpec, UpdateBackground, UpdateMeta, Batch.
3. `UndoManager(capacity = 50)`: push, undo, redo, coalescing (same key within 1000 ms), redo cleared on push, `StateFlow` canUndo/canRedo.
Accept:
- [ ] unit: property test, 200 random commands then full undo returns the original document (seeded)
- [ ] unit: capacity drops oldest; coalescing merges a typing burst into one entry
Verify: `./gradlew :core:model:test`

### P02-T03 Protobuf schema and codecs
Implements: R-FILE-02, R-FILE-04
Read: 04-file-format.md#protobuf-schema, #stroke-encoding
Files: core/format/src/main/proto/folio/v1/*.proto, core/format/.../codec/**
Do:
1. Wire plugin (Kotlin output). Schema from 04-file-format.md#protobuf-schema.
2. Codecs model <-> proto; StrokeInputs quantized delta encoding (1/64 pt, 0.1 ms, pressure 0..1023, angles 0.1 deg).
Accept:
- [ ] unit: round trip equal within 1/128 pt for all object types
- [ ] unit: golden files `testdata/format/v1/*.pb` decode to expected models
- [ ] unit: 1000 strokes x 120 inputs encode to < 1.2 MB before deflate
Verify: `./gradlew :core:format:test`

### P02-T04 Manifest and container
Implements: R-FILE-02
Read: 04-file-format.md#container-layout, #manifest, #versioning
Files: core/format/.../container/**, testdata/format/v1/sample.folio
Do:
1. Manifest model (kotlinx.serialization, ignoreUnknownKeys).
2. `FolioContainerReader` (random access with ZipFile) and `FolioContainerWriter` (mimetype first and STORED; assets STORED; pb/json/md DEFLATED level 6).
3. Migration registry and typed errors: `FormatTooNew`, `Corrupt(entry)`, `MissingEntry`.
Accept:
- [ ] unit: golden `sample.folio` opens; unknown JSON keys ignored; newer major version rejected with FormatTooNew
- [ ] unit: produced zip lists `mimetype` first (check with ZipInputStream)
Verify: `./gradlew :core:format:test`

### P02-T05 Working copy and packer
Implements: R-FILE-01, R-FILE-02
Read: 04-file-format.md#write-protocol, #crash-recovery, #conflicts
Files: core/storage/.../work/**
Do:
1. `WorkingCopy` in `files/work/<docId>/`: entries + `base.json` (source path, source size/mtime, manifest modified, dirty entries, lastPackAt).
2. Entry autosave with 1 s debounce, atomic per entry.
3. `Packer`: build `<name>.folio.tmp` next to the target from the working copy, fsync, rename; previous version kept as app-private backup `files/backup/<docId>.folio` (1 generation).
4. Recovery at startup: dirty working copies are packed; UI notified via `RecoveryEvents`.
5. Conflicts: source changed externally while working copy dirty -> pack to `<title> (conflict YYYY-MM-DD HH-mm).folio` beside it.
Accept:
- [ ] unit: injected failure at random byte offsets during pack never corrupts the target (seeded, 100 runs)
- [ ] unit: recovery packs dirty copies; conflict copy created in the conflict case
Verify: `./gradlew :core:storage:testDebugUnitTest`

### P02-T06 Library root and permission flow
Implements: R-FILE-01
Read: 09-storage-library.md#permission, #library-layout
Files: core/storage/.../library/**, feature/library (onboarding placeholder)
Do:
1. `LibraryConfig`, `StoragePermission` (Environment.isExternalStorageManager), `JavaFileFolioFs`.
2. Onboarding screen: explains all-files access in two sentences, button opens `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` for our package; re-check on resume.
3. On grant: create root with `.trash/` and `.templates/`.
Accept:
- [ ] device: fresh install shows onboarding; after `grant-storage.sh` and relaunch the library placeholder shows and the folders exist (`debugcmd.sh state`)
Verify: `./gradlew :app:assembleDebug` + device-tester

### P02-T07 Library index
Implements: R-ORG-01, R-ORG-03
Read: 09-storage-library.md#index, #scanning
Files: core/storage/.../index/**
Do:
1. Room `IndexDb` v1: documents, folders, tags, links, `doc_fts` (FTS4 over title, body).
2. `LibraryScanner`: full scan (skip hidden dirs; read `manifest.json` + `search/text.txt` only), incremental scan (size/mtime), FileObserver watcher for visible folders. Batched transactions.
Accept:
- [ ] unit (Robolectric, temp dir): 500 generated documents scanned < 3 s; add/modify/delete/move detected; FTS prefix search works
Verify: `./gradlew :core:storage:testDebugUnitTest`

### P02-T08 Repositories
Implements: R-ORG-01, R-ORG-04
Read: 09-storage-library.md#repositories, #trash
Files: core/storage/.../repo/**
Do:
1. `LibraryRepository`: folder tree, document lists (folder, favorites, tag, bin; sort recent/name/created), search, tags, favorites.
2. `DocumentRepository`: create from `NewDocumentSpec`, duplicate, rename, move, delete to bin, restore, delete forever, empty bin, purge by retention.
Accept:
- [ ] unit: every operation incl. restore to original path, name conflict suffix " (2)", retention purge
Verify: `./gradlew :core:storage:testDebugUnitTest`

### P02-T09 Document session lifecycle
Implements: R-FILE-05, R-PERF-02
Read: 03-document-model.md#sessions, 04-file-format.md#write-protocol
Files: core/storage/.../session/**
Do:
1. `DocumentSession`: open (reuse valid working copy), `StateFlow<Document>`, lazy page decoding with LRU of 30 decoded pages, `execute(command)`, UndoManager, dirty tracking -> autosave, pack triggers (close, app onStop via ProcessLifecycleOwner, 30 s timer), thumbnail hook (interface).
Accept:
- [ ] unit: open -> edit -> close -> reopen equality; <= 30 decoded pages held; close triggers pack
Verify: `./gradlew :core:storage:testDebugUnitTest`
