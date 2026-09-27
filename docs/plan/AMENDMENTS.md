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

## A-002 2026-09-27 P00-T04: core:common and core:testing are JVM modules; bytecode 17 without a toolchain
- Change: `core:common` and `core:testing` are Kotlin/JVM modules (spec: Android libraries). `core:testing -> core:model, core:common` is an allowed edge (test-only consumer). No Gradle JVM toolchain: JDK 21 runs the build, bytecode target is 17 via compileOptions / jvmTarget / `--release 17`. build-tools pinned to the installed 37.0.0; compileSdk 37 minor 2.
- Reason: `core:model` and `core:format` are JVM modules and must depend on `core:common` and use `core:testing` in tests; a JVM module cannot depend on an Android library. Both modules only hold pure Kotlin APIs (Android sinks for FolioLog/PerfMonitor tracing live in :app). A toolchain of 17 needs a JDK 17 install or network auto-provisioning; only JDK 21 is installed (env.md). AGP 9.4.1 defaults to build-tools 36.0.0, which is not installed and would trigger a license-bearing download.
- Impact: 02-modules.md#module-list and graph; root `verifyModuleGraph` encodes the graph.
