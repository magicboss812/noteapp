---
paths:
  - "**/*.gradle.kts"
  - "build-logic/**"
  - "gradle/**"
  - "gradle.properties"
---
# Gradle rules
- Versions live only in `gradle/libs.versions.toml`. Exact pins, no `+`, no ranges, no `latest.release`.
- Alpha/beta only where `docs/architecture/decisions.md` allows it (androidx.ink 1.1.0-alpha; detekt 2.0 alpha per A-001).
- Convention plugins in `build-logic`: `folio.android.application`, `folio.android.library`, `folio.jvm.library`, `folio.compose`, `folio.hilt`, `folio.room`, `folio.wire`, `folio.screenshot`, `folio.quality`. Module build files stay short (target <= 25 lines).
- Adding a dependency: license must be Apache-2.0/MIT/BSD/ISC/OFL (GPL only with classpath exception and an ADR note; EPL-1.0 only for test-only artifacts such as JUnit4, A-004). Check it adds no INTERNET permission. Add a row to `docs/notes/env.md` Dependencies (name, version, license, reason).
- Configuration cache and build cache on. `org.gradle.jvmargs` at least `-Xmx4g`.
- `verifyNoInternet` stays wired into `qa`. Never remove a check from `qa` to make a task pass.
- Never commit `local.properties`, keystores or `keystore.properties`. Release signing reads `keystore.properties` if present, else builds unsigned.
- Build types: `debug` (suffix `.debug`), `release` (R8 full mode, shrinkResources), `benchmark` (added in P11, suffix `.benchmark`, debuggable false, signed with debug key).
