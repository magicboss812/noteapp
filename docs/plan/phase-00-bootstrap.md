# P00 Bootstrap
Goal: reproducible toolchain, device link, pinned versions, compilable multi-module skeleton, quality gates, test infrastructure, debug automation. No product features.
Exit: app launches on the tablet via wrappers; `./gradlew qa` green; tag `p00-done`.

### P00-T01 Environment doctor
Implements: R-DEV-03
Read: CLAUDE.md (loaded)
Files: docs/notes/env.md
Do:
1. Run `bash scripts/env/doctor.sh`.
2. FAIL lines -> Blocked item per missing tool with the exact install command for this OS (Windows: winget; Arch: pacman). SDK licenses are always the user's job. Stop if JDK or SDK is missing.
3. With cmdline-tools present, install missing SDK packages yourself: `sdkmanager "platform-tools" "platforms;android-<latest stable>" "build-tools;<latest stable>"`.
4. Write docs/notes/env.md (<= 40 lines): OS, shell, JDK, SDK path, platforms, build-tools, git, jq, date. Add a "Machines" table; the second machine gets a row when it first runs doctor.
Accept:
- [ ] doctor prints `DOCTOR: PASS`
- [ ] docs/notes/env.md exists with real values
Verify: `bash scripts/env/doctor.sh`

### P00-T02 Device handshake
Implements: R-DEV-01, R-DEV-04
Read: .claude/skills/device-test/SKILL.md
Files: docs/notes/device.md
Do:
1. `bash scripts/device/connect.sh`. On failure: Blocked "pair the tablet (README Tablet section)"; continue with non-device tasks and mark device accept items as pending in Handoff.
2. `bash scripts/device/info.sh`. Fill docs/notes/device.md sections: Identity, Display (resolution, density, refresh modes), Input (stylus device names/sources), Quirks (empty).
3. minSdk = min(35, device SDK). Record it in device.md and as a dated note under ADR-012 in decisions.md.
Accept:
- [ ] device.md has real values (or a Blocked item exists)
Verify: `bash scripts/device/info.sh`

### P00-T03 Resolve and pin versions
Implements: R-DEV-02
Read: 02-modules.md#dependencies, decisions.md#adr-002-ink-stack
Files: gradle/libs.versions.toml, docs/notes/env.md
Do:
1. For every entry in 02-modules.md#dependencies find the latest stable version (alpha only where marked) from primary sources: `https://dl.google.com/android/maven2/<group/path>/maven-metadata.xml`, `https://repo1.maven.org/maven2/<group/path>/maven-metadata.xml`, AndroidX release pages, project GitHub releases.
2. Check compatibility: Kotlin <-> Compose compiler plugin <-> KSP <-> AGP <-> Gradle <-> JDK. Use the official compatibility pages.
3. Write gradle/libs.versions.toml (versions, libraries, plugins, bundles). Add env.md "Dependencies" table: name, version, license, source URL, reason.
4. RaTeX, PdfBox-Android, Roborazzi: confirm exact Maven coordinates before pinning (they are verified again in P01 spikes).
Accept:
- [ ] no `+`, ranges, or `latest` in the catalog
- [ ] every version has a source row in env.md
- [ ] androidx.ink pinned to the newest 1.1.0-alphaNN
Verify: `grep -nE '\+"|latest|SNAPSHOT' gradle/libs.versions.toml` prints nothing

### P00-T04 Gradle skeleton and modules
Implements: R-DEV-02
Read: 02-modules.md#module-list, 02-modules.md#build-logic, .claude/rules/gradle.md
Files: settings.gradle.kts, build.gradle.kts, gradle.properties, build-logic/**, every module's build.gradle.kts, app/src/main/**
Do:
1. Gradle wrapper (latest stable), settings with all modules from 02-modules.md#module-list, included build `build-logic` with the convention plugins from the gradle rule.
2. If AGP >= 9: read the official AGP 9 migration notes first and use its built-in Kotlin support and DSL; otherwise the Kotlin Android plugin.
3. `:app`: namespace `dev.folio.app`, applicationId `dev.folio.notes`, debug `applicationIdSuffix ".debug"`, versionName `0.1.0`. compileSdk/targetSdk = latest stable, minSdk from P00-T02. JVM toolchain 17 unless AGP requires 21.
4. `MainActivity` (Compose, edge-to-edge) showing "Folio", build type, version. `@HiltAndroidApp` Application.
5. Manifest: MANAGE_EXTERNAL_STORAGE declared; `android:largeHeap="true"`; `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>` and same for ACCESS_NETWORK_STATE.
6. `LibraryConfig` via BuildConfig fields: release root `Documents/Folio`, debug `Documents/Folio-Debug`.
7. Each module has one placeholder unit test so test tasks exist.
Accept:
- [ ] `./gradlew assembleDebug test` succeeds from a clean checkout
- [ ] module graph matches 02-modules.md (no extra edges)
Verify: `./gradlew clean assembleDebug test`

### P00-T05 Quality gates
Implements: R-DEV-02
Read: .claude/rules/gradle.md
Files: build-logic (folio.quality), config/detekt/detekt.yml, config/lint/lint.xml
Do:
1. Spotless + ktlint for `*.kt` and `*.kts`.
2. detekt with Compose rules plugin; thresholds relaxed only for `@Composable` functions (LongMethod, LongParameterList).
3. Android lint: abortOnError true; `lint.xml` keeps defaults; NewApi, MissingPermission, WrongThread as errors.
4. `verifyNoInternet`: after manifest merge of every app variant, fail if INTERNET or ACCESS_NETWORK_STATE is present.
5. Root task `qa` = spotlessCheck + detekt + lintDebug (all Android modules) + all unit tests + verifyRoborazziDebug (once present) + verifyNoInternet.
Accept:
- [ ] `./gradlew qa` green
- [ ] adding INTERNET to the app manifest makes `verifyNoInternet` fail (try, confirm, revert)
Verify: `./gradlew qa`

### P00-T06 Test infrastructure
Implements: R-DEV-03
Read: .claude/rules/testing.md
Files: core/testing/**, build-logic (folio.screenshot), testdata/README.md
Do:
1. `core:common`: `FolioFs` interface, `Clock`, `FolioDispatchers`, `Outcome`, `FolioLog`, `PerfMonitor` (named sections, ring buffer of durations, p50/p95/max; no-op in release).
2. `core:testing`: FakeClock, TestDispatchers rule, TempDirFolioFs, document builders (filled in P02).
3. JVM tests: JUnit4, Truth, Turbine, kotlinx-coroutines-test. Robolectric + Roborazzi via `folio.screenshot` convention plugin; one screenshot test of the MainActivity content.
4. Instrumented: AndroidJUnitRunner (Hilt test runner in app), one smoke test.
Accept:
- [ ] `./gradlew recordRoborazziDebug` then `./gradlew verifyRoborazziDebug` green
- [ ] device: `bash scripts/device/instrumented.sh :app` smoke test passes
Verify: `./gradlew qa`

### P00-T07 Debug automation hooks
Implements: R-DEV-04
Read: .claude/skills/device-test/SKILL.md, 12-performance.md#measurement
Files: app/src/debug/kotlin/dev/folio/app/debug/**, app/src/main (no-op interfaces)
Do:
1. `DebugHooks` interface in main (no-op binding in release), implemented in the debug source set.
2. MainActivity forwards intents with extras `folio.debug.cmd/arg/nonce` (onCreate and onNewIntent) and `folio.debug.doc/route` to DebugHooks.
3. `DebugCommands` registry with an initial set: `state` (JSON: screen, route, open doc, tool, zoom), `perf-reset`, `perf-dump` (PerfMonitor percentiles JSON), `route <name>`. Later phases add commands (open, new, tool, seed-strokes, zoom-anim, pack, export-pdf, export-png, ...). Each reply is one logcat line, tag FolioDebug, level I: `nonce=<n> ok=<bool> <json>`.
4. Debug-only on-screen frame-time overlay toggled by `debugcmd.sh overlay on|off`.
Accept:
- [ ] device: `bash scripts/device/debugcmd.sh state` returns `ok=true` with JSON
- [ ] release APK contains no `DebugCommands` class (check with `apkanalyzer dex packages` or by grepping the dex list of `./gradlew :app:assembleRelease` output)
Verify: `./gradlew :app:assembleDebug :app:assembleRelease qa`

### P00-T08 First device run
Implements: R-DEV-04
Read: .claude/skills/device-test/SKILL.md
Do:
1. device-tester: build, install, grant-storage, launch, screenshot, `logcat.sh 100`, `debugcmd.sh state`.
2. Record any HyperOS quirks (install prompts, permission dialogs) in docs/notes/device.md.
Accept:
- [ ] device: screenshot shows the Folio placeholder screen; no errors from our package in logcat
Verify: device-tester report PASS
