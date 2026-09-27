# P05 Library
Goal: the Notewise-inspired home: sidebar with folders, greeting header, tabs, sorting, grid/list, cards with previews, tags, search, bin, and fluid transitions.
Exit: usable daily as an ink notebook app; tag `p05-done`.

### P05-T01 Library layout
Implements: R-ORG-04, R-UI-01, R-UI-04
Read: 11-design-system.md#library-screen, docs/design/reference/README.md
Files: feature/library/**
Do: collapsible sidebar (Home, folder tree, Settings), header greeting (name from settings, date subtitle), tabs All / Favorites / Tags / Bin, sort menu (Recent default, Name, Created), grid/list toggle, select-mode toggle, search button, "New" button menu (Note, Folder, Import PDF, Import .folio).
Accept:
- [ ] screenshot: empty and populated, landscape and portrait, light and dark
Verify: `./gradlew :feature:library:verifyRoborazziDebug`

### P05-T02 Cards and selection
Implements: R-ORG-04
Read: 11-design-system.md#cards
Do: NoteCard (meta line, title, preview = cover thumbnail or text excerpt, overflow menu: rename, move, duplicate, favorite, tags, export, delete), FolderCard (tint, stacked sheets, count), multi-select with batch move/delete/favorite.
Accept:
- [ ] screenshot: grid with mixed cards; selection mode
- [ ] unit: preview choice logic
Verify: module tests + screenshots

### P05-T03 Folders
Implements: R-ORG-01
Read: 09-storage-library.md#library-layout
Do: create, rename, tint color (stored in `.folder.json`), move (dialog with tree), delete to bin, breadcrumb, nested navigation.
Accept:
- [ ] unit: folder ops on temp dir; restore of a deleted folder with contents
- [ ] device: create nested folders via UI routes; they exist on disk
Verify: tests + device-tester

### P05-T04 Tags and favorites
Implements: R-ORG-01, R-ORG-04
Do: tag editor dialog (suggestions from index), Tags tab (tag chips -> filtered grid), favorite toggle everywhere; both stored in manifest and mirrored in the index.
Accept:
- [ ] unit: manifest update + index mirror
Verify: module tests

### P05-T05 Search
Implements: R-ORG-03
Read: 09-storage-library.md#search
Do: search overlay with instant results (FTS, prefix), highlighted snippets, filters (folder, tag), recent searches (local only), open at first match page.
Accept:
- [ ] unit: ranking (title match first), snippet highlighting
- [ ] performance: 500 docs query < 100 ms (JVM test)
Verify: module tests

### P05-T06 Bin
Implements: R-ORG-04
Read: 09-storage-library.md#trash
Do: Bin tab with days-left badge, restore, delete forever (confirm), empty bin (confirm), daily WorkManager purge with retention setting (default 30 days).
Accept:
- [ ] unit: purge by retention with FakeClock
- [ ] screenshot: bin list and confirm dialog
Verify: module tests + screenshots

### P05-T07 Transitions
Implements: R-UI-01
Read: 11-design-system.md#motion
Do: shared element card -> editor (container transform), predictive back from editor, animated grid item changes, sidebar expand/collapse.
Accept:
- [ ] user: "Open and close notes from the library. Smooth, no flashes, no jumps?"
Verify: device-tester smoke

### P05-T08 Library screenshot suite
Do: goldens for empty library, 12 notes, folders, search results, bin, dark, portrait/landscape; fix visual defects found.
Accept:
- [ ] screenshot: suite recorded and verified
Verify: `./gradlew :feature:library:verifyRoborazziDebug`
