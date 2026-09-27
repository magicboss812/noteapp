---
name: maintain-memory
description: Keep CLAUDE.md, rules, skills, STATUS.md and docs/notes lean and correct. Use when a size hook fires, at every phase REVIEW, and whenever you learned something future sessions need.
---
# maintain-memory

## Where does a fact go? (first match wins)
1. Needed in every session, short, stable -> CLAUDE.md `## Learned` (one line, dated, task id).
2. Only relevant for certain files -> `.claude/rules/<topic>.md` with correct `paths:`.
3. A multi-step procedure -> a skill (new or existing).
4. Explanation, measurements, investigation -> `docs/notes/<topic>.md`; link one line from 1 or 2 only if it changes behavior.
5. Changes the design -> architecture doc + AMENDMENTS entry.

## Triggers to record something
- The same error, correction, or workaround happened twice.
- A tooling or build quirk (Windows vs Arch differences included).
- A device or HyperOS behavior.
- A library API gotcha or version constraint.
- A decision the user made in chat.

## Compaction procedure (hook fired, or phase REVIEW)
1. CLAUDE.md Learned: move path-specific entries into rules; merge duplicates; delete entries now enforced by code, tests, or lint; move stale detail to `docs/notes/gotchas.md`.
2. Rules: delete lines duplicated elsewhere; split any file approaching 120 lines by sub-topic with narrower `paths:`.
3. STATUS.md: finished phases = one line each; Handoff <= 5 lines; Deferred = id + one clause.
4. docs/notes: summarize old sections when a file nears 200 lines.
5. Check: `wc -l CLAUDE.md .claude/rules/*.md docs/plan/STATUS.md .claude/skills/*/SKILL.md`.
6. Commit `docs(memory): compact [<task or phase>]`.

## Never
- Never store secrets, tokens, or personal data.
- Never weaken or reword the hard constraints in CLAUDE.md without the user's explicit approval recorded in AMENDMENTS.md.
- Never edit protected config; propose it as a Blocked item with the exact diff.
