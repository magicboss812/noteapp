---
paths:
  - "**/*.kt"
  - "**/*.kts"
---
# Kotlin rules
- Official Kotlin style, enforced by spotless/ktlint. Run `./gradlew spotlessApply` before committing.
- Package root `dev.folio`; package mirrors module path (`:core:text` -> `dev.folio.core.text`).
- Immutable by default: `val`, data classes, `kotlinx.collections.immutable` for document state.
- No `!!` outside tests. No `lateinit` in domain code. Sealed interfaces + exhaustive `when` for variants.
- Expected failures return `Outcome<T>` (core:common). Throw only for programmer errors. Never swallow exceptions; log via `FolioLog`.
- Coroutines: inject `FolioDispatchers` (main, io, render, pdf, text). No `GlobalScope`, no `runBlocking` on main, cancellation-safe IO.
- Hot paths (input, render, text layout, typing) carry a `// HOT PATH` comment: no per-event allocation, no boxing, no logging, no Flow emission per point.
- `internal` by default; public only for cross-module API, each with a one-line KDoc.
- Units in names when ambiguous: `widthPt`, `sizePx`, `durationMs`, `angleDeg`. Page space = PDF points.
- Annotate non-obvious threading with `@MainThread` / `@WorkerThread`.
- JSON: kotlinx.serialization. Protobuf: Wire. No Gson, no Java serialization, no reflection-based mappers.
- Logging: `FolioLog.d/i/w/e(tag, msg)`. Debug and verbose logs are stripped in release via R8 rules.
- Time: inject `Clock` (core:common) instead of `System.currentTimeMillis()` in logic.
