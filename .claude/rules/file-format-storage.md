---
paths:
  - "core/format/**"
  - "core/storage/**"
---
# File format and storage rules
- `docs/architecture/04-file-format.md` is normative. Any format change: bump version, add migration + golden test, update the doc in the same commit.
- Wire `.proto` files in `core/format/src/main/proto/folio/v1/`. Never reuse field numbers; removed fields become `reserved`.
- All writes atomic: temp file in the same directory -> flush + fsync -> rename. Never write a user file in place.
- Working copies live in app-private `files/work/<docId>/`. Entry autosave after 1 s idle; pack on editor close, `onStop`, and every 30 s while dirty.
- Permanent deletion only via user actions (Delete forever, Empty bin) and bin auto-purge.
- Library root comes from `LibraryConfig` (release `Documents/Folio`, debug `Documents/Folio-Debug`). Never hardcode it.
- Room index is disposable: schema change = version bump + destructive migration + full rescan.
- File IO only on the io dispatcher, through the injected `FolioFs` so tests use temp dirs.
- Treat every file from the library folder as untrusted input: bounded sizes, typed errors, open corrupted files read-only with a message.
