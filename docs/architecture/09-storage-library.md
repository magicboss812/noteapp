# 09 Storage and library

## Permission
- The library lives in shared storage so the user can copy files over USB and other apps (R-FILE-01). Access uses `MANAGE_EXTERNAL_STORAGE` (All files access) + `java.io.File` (ADR-004). Acceptable because the app is sideloaded for personal use; SAF DocumentFile would be slow for scanning and random-access ZIP.
- First launch: onboarding explains the permission in two sentences and opens `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` for our package. The library screen re-checks `Environment.isExternalStorageManager()` on every resume; if revoked, a blocking banner with the same button replaces the grid (open editors pack to the working copy only until access returns).
- Debug builds can be granted via `scripts/device/grant-storage.sh`.
- Measured (P01-S6, ADR-004): java.io on the HyperOS FUSE mount packs a 46 MB `.folio` in 1.39..1.45 s (write dominates), the tmp-fsync-rename protocol survived kills mid-pack, and FileObserver delivers external writes (e.g. over USB) as CREATE/CLOSE_WRITE.

## Library layout
```
/sdcard/Documents/Folio/            (debug: Folio-Debug/; configurable later)
  Physics/                          folder = directory; nesting unlimited
    .folder.json                    optional: {"tint":"blue","sort":"recent"}
    Kinematics.folio
  Journal 2026.folio
  Exports/                          default export destination
  .trash/                           bin (see Trash)
  .templates/                       custom templates (PNG/PDF) available to all documents
  .fonts/                           imported custom fonts (mirrored for portability)
```
- Hidden entries (names starting with `.`) are never shown as folders. File names are derived from titles (invalid characters replaced by `-`, max 120 chars, collision suffix " (2)").
- Renaming a document renames the file and updates the manifest title. Moving = file move (same volume, atomic).

## Index
Room database `index.db` in app-private storage. Disposable: can be deleted and rebuilt by a full scan.
| Table | Columns |
|---|---|
| documents | path (PK, library-relative), docId (indexed), folderPath, title, createdMs, modifiedMs, favorite, pageCount, textPreview, fileSize, fileMtime, coverThumbPath, formatVersion, status (OK, TOO_NEW, CORRUPT) |
| folders | path (PK), parentPath, name, tint, createdMs |
| tags | path (FK documents, cascade), tag (PK both) |
| links | fromPath (FK documents, cascade), toDocId (PK both) |
| doc_fts | FTS4 (unicode61 tokenizer): path (not indexed), title, body |
Duplicate docIds (a file copied manually): the second file gets a new docId on first open (manifest rewritten); until then the index keeps both rows, which is why rows are keyed by path (A-013). Unreadable files get a row with the file name as title and status TOO_NEW or CORRUPT.

## Scanning
- Full scan: walk the root (skip hidden dirs), for each `.folio` read only `manifest.json` and `search/text.txt` via ZipFile random access, upsert rows in batched transactions (200 per transaction). Runs on first launch, when the root changes, and from Settings "Rebuild index".
- Incremental scan on every app foreground: compare size + mtime of each file with the index; re-read changed ones; remove rows for vanished files; detect moves by docId.
- Live updates while running: `FileObserver` on the currently displayed folder and on folders of open documents; our own writes update the index directly (no rescan).
- Budget: 500 documents full scan < 3 s on the tablet; incremental with no changes < 300 ms.

## Repositories
- `LibraryRepository`: `folderTree(): Flow`, `documents(folder, filter, sort): Flow` (filters: all, favorites, tag, bin), `search(query): Flow`, `setFavorite`, `setTags`, folder CRUD (create, rename, tint, move, delete to bin).
- `DocumentRepository`: `create(NewDocumentSpec)`, `importPdf(uri)`, `importFolio(uri)`, `open(docId): DocumentSession`, `duplicate`, `rename`, `move`, `delete` (to bin), `restore`, `deleteForever`, `emptyBin`, `purgeBin(retention)`.
- All operations are suspend functions on the io dispatcher returning `Outcome`.
- Implementation notes (A-014): operations take library-relative paths and act on closed documents (open ones change through their session). Favorite, tags, rename and duplicate rewrite the `.folio` (all entries streamed, `manifest.json` patched as JSON so unknown keys survive; rename also rewrites the first line of `search/text.txt`). A duplicate gets a new docId and the title of its file name ("X (2)"). The bin list comes from the `.trash` sidecars, not the index. `importPdf` arrives with P08-T01, `importFolio` with the P05 New menu, `open` returns the P02-T09 session.

## Trash
- Deleting a document or folder moves it to `.trash/<epochMs>__<name>` plus sidecar `<same>.trash.json` `{ "originalPath": "Physics/Kinematics.folio", "deletedMs": ..., "docId": ... }`.
- Bin tab lists trash entries with "deleted N days ago / M days left". Restore moves back to the original path (recreating folders; name collision -> " (2)").
- Retention (settings): 7, 30 (default), 90 days, never. A daily WorkManager job and every app start purge expired entries. "Delete forever" and "Empty bin" ask for confirmation.
- Trashed documents are excluded from search and links resolution (links show as broken).

## Search
- FTS4 over title + body where body = `search/text.txt` (plain text of all flows, Markdown syntax stripped, math source kept as text). Handwriting is not searchable (R-ORG-03).
- Query: tokens with prefix matching (`term*`), all tokens must match. Ranking: title matches first, then by modifiedMs.
- Results show title, folder, snippet with highlighted matches (FTS snippet()). Opening a result jumps to the first page containing the first match (page lookup via flow layout at open).
- Recent searches: last 10, stored locally in DataStore.

## Links
- Note links are Markdown links `[Title](folio://doc/<docUuid>)` (optionally `#page=<pageId>`). The editor inserts them via the `[[` picker (search by title).
- Resolution through the index by docId, so renames and moves never break links. Missing target: link rendered struck-through with a "not found" tooltip.
- Backlinks: `links` table filled from each manifest's `links` array (maintained at pack time from the flows). The editor's overflow menu shows "Linked from" with a list.
- Web links (`https://`) open via ACTION_VIEW in the user's browser (Folio itself has no network access).

## Thumbnails
- Cover: first page rendered at <= 512 px long side (WebP q80), stored in the document (`thumbs/cover.webp`) at pack time and cached in app cache for the library grid.
- Page thumbnails: <= 256 px, generated after 2 s editing idle on the render dispatcher for pages changed since the last thumbnail; used by the page panel; stored in the document at pack time (optional entries).
- Text-heavy documents: the library card shows `textPreview` instead of the image when the first page's objects are mostly text frames (ratio of text frame area to object area > 0.6).
