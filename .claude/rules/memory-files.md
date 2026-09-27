---
paths:
  - "CLAUDE.md"
  - ".claude/**"
  - "docs/plan/STATUS.md"
  - "docs/notes/**"
---
# Memory file rules
- CLAUDE.md: facts needed in every session only. <= 200 lines (hook-enforced). No history, no task logs.
- Path-specific knowledge -> the matching `.claude/rules/<topic>.md` with accurate `paths:`.
- Multi-step procedures -> `.claude/skills/<name>/SKILL.md`.
- Explanations, measurements, investigations -> `docs/notes/<topic>.md`, linked by one line where it changes behavior.
- STATUS.md: <= 60 lines, fixed format (docs/plan/PLAN.md "STATUS format"). Finished phases collapse to one line.
- One fact, one place. Link instead of duplicating.
- Settings, hooks, and device scripts are protected: propose changes as a Blocked item containing the exact diff.
