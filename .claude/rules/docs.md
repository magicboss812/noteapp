---
paths:
  - "docs/**"
---
# Documentation rules
- Architecture docs are normative. Code that disagrees with a doc is a bug in one of them: fix the code, or amend the doc with an AMENDMENTS entry, in the same commit.
- Headings are anchors referenced by tasks (`file.md#heading-slug`). Do not rename headings; add new ones.
- Phase files are specifications, not trackers. Progress lives only in STATUS.md.
- decisions.md: status changes (Proposed -> Accepted/Superseded) need a dated evidence line (numbers from spikes or tests).
- docs/notes files stay under 200 lines each; summarize older content instead of appending forever.
- No em-dashes in docs or UI copy.
