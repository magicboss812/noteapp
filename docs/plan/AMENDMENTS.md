# Amendments
Every deviation from docs/architecture or docs/plan is recorded here before or with the change. Newest last.

Format:
## A-NNN YYYY-MM-DD <task-id>: <title>
- Change: what is different from the spec
- Reason: evidence (numbers, errors, API facts)
- Impact: affected docs sections and task ids (updated in the same commit)

## A-001 2026-09-27 P00-T03: detekt 2.0 alpha and hilt-lifecycle-viewmodel-compose
- Change: detekt pinned to 2.0.0-alpha.6 (plugin id `dev.detekt`) instead of a stable release; `androidx.hilt:hilt-lifecycle-viewmodel-compose` replaces `hilt-navigation-compose`.
- Reason: the latest compose-rules (io.nlopez.compose.rules:detekt 0.6.7) depends on dev.detekt:detekt-core 2.0.0-alpha.6 (its POM); the only stable detekt (1.23.8) is built on Kotlin 2.0.21 while the project uses Kotlin 2.4.20 and AGP 9. With Navigation 3 (stable 1.2.0, ADR-013) `hiltViewModel()` lives in hilt-lifecycle-viewmodel-compose; hilt-navigation-compose targets navigation-compose. detekt is build-time only, nothing ships.
- Impact: 02-modules.md#dependencies (DI and Quality rows), decisions.md ADR-012 note, .claude/rules/gradle.md alpha list. Revisit when detekt 2.0.0 is stable.
