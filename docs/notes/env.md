# Environment
Filled by P00-T01 and P00-T03. Keep <= 200 lines.

## Machines
| Machine | OS / shell | JDK | Android SDK path | Last doctor run |
|---|---|---|---|---|
| surface (Surface laptop) | Arch Linux, kernel 6.19.8-arch1-3-surface / bash 5.3.20 | OpenJDK 21.0.12.1 (/usr/lib/jvm/java-21-openjdk, JAVA_HOME unset) | /opt/android-sdk (chowned to the user) | 2026-09-27: PASS |

Surface SDK (2026-09-27): platform-tools 37.0.1, build-tools 37.0.0, cmdline-tools 23.0, platforms android-37.0 + android-37.2 (latest stable). git 2.55.0, jq 1.8.2.

## Android CLI (cmdline-tools 23)
- `sdkmanager` is deprecated and delegates to `android sdk` (`cmdline-tools/latest/bin/android`). Package names use slashes: `android sdk install platforms/android-37.0`.
- There is no separate license step (`sdkmanager --licenses` prints "no longer needed"); installing a package accepts the license, so the USER runs installs of new SDK packages.
- The SDK dir must be writable by the user (Arch AUR installs it root-owned: `sudo chown -R $USER:$USER /opt/android-sdk`).

## Toolchain versions
Resolved 2026-09-27 (P00-T03) from maven-metadata.xml; all 83 catalog coordinates checked to exist.
- Gradle 9.8.0, AGP 9.4.1 (built-in Kotlin, min Gradle 9.6.0, max API 37), Kotlin 2.4.20, KSP 2.3.12, JDK 21 runs the build.
- compileSdk 37 minor 2 (`compileSdkMinor`), targetSdk 37, minSdk 35, build-tools 37.0.0 (A-002).
- Compose BOM 2026.09.00. Alphas: androidx.ink 1.1.0-alpha09 (ADR-002), detekt 2.0.0-alpha.6 (A-001).

## Dependencies
| Name | Version | License | Source URL | Reason |
|---|---|---|---|---|
| Gradle wrapper | 9.8.0 | Apache-2.0 | https://services.gradle.org/versions/current | build tool; AGP 9.4 needs >= 9.6.0 |
| Android Gradle Plugin | 9.4.1 | Apache-2.0 | https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml | build; max API 37, JDK 17+ (developer.android.com AGP 9.4 notes) |
| Kotlin (KGP, compose + serialization plugins) | 2.4.20 | Apache-2.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml | language; replaces AGP built-in KGP default |
| KSP | 2.3.12 | Apache-2.0 | https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml | Hilt/Room codegen; KSP2 works with Kotlin 2.4 + AGP 9 built-in Kotlin (release notes 2.3.6/2.3.10) |
| Compose BOM | 2026.09.00 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/compose/compose-bom/maven-metadata.xml | UI (ui 1.12.1, material3 1.4.0); core:text uses only ui-text (Paragraph layout) |
| Bundled text fonts (20 families, 07-text-engine.md#fonts) | google/fonts `main`, fetched 2026-09-27 | OFL-1.1 | https://raw.githubusercontent.com/google/fonts/main/ofl/ | core/text/src/main/res/font, licenses in core/text/src/main/assets/licenses (P01-S3) |
| UI fonts Literata + Inter (11-design-system.md#typography) | google/fonts `main` (core:text copies) | OFL-1.1 | https://raw.githubusercontent.com/google/fonts/main/ofl/ | core/designsystem/src/main/res/font, licenses in core/designsystem/src/main/assets/licenses; `inter_var.ttf` and `literata_var.ttf` are the core:text files under the same resource names, so the APK merge keeps one copy (P04-T01, Literata replaced Fraunces in P04-T10) |
| Lucide icons (69 SVGs, pinned tag) | 1.49.0, fetched 2026-09-30 | ISC | https://raw.githubusercontent.com/lucide-icons/lucide/1.49.0/icons/ | tools/icongen/src/main/resources/lucide (LICENSE, VERSION); generated into core:designsystem `FolioIcons.kt` (P04-T01) |
| activity-compose | 1.13.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/activity/activity-compose/maven-metadata.xml | Compose host activity |
| lifecycle (runtime-compose, viewmodel-compose, process, viewmodel-navigation3) | 2.11.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/lifecycle/maven-metadata.xml | state, app lifecycle |
| core-ktx | 1.19.1 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/core/core-ktx/maven-metadata.xml | platform helpers |
| window | 1.5.1 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/window/window/maven-metadata.xml | window metrics |
| material3-adaptive | 1.3.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/compose/material3/adaptive/adaptive/maven-metadata.xml | window size classes |
| navigation3 (runtime, ui) | 1.2.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/navigation3/maven-metadata.xml | navigation (ADR-013) |
| Hilt (android, compiler, testing, gradle plugin) | 2.60.1 | Apache-2.0 | https://repo1.maven.org/maven2/com/google/dagger/hilt-android/maven-metadata.xml | DI |
| androidx.hilt (lifecycle-viewmodel-compose, work, compiler) | 1.4.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/hilt/maven-metadata.xml | hiltViewModel with Nav3 (A-001), Hilt workers |
| kotlinx-coroutines (core, android, test) | 1.11.0 | Apache-2.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml | async |
| kotlinx-serialization-json | 1.11.0 | Apache-2.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml | manifest JSON |
| kotlinx-collections-immutable | 0.5.2 | Apache-2.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-collections-immutable/maven-metadata.xml | stable Compose state |
| Room (runtime, ktx, compiler, testing, gradle plugin) | 2.8.5 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/room/maven-metadata.xml | library index + FTS |
| DataStore Preferences | 1.2.1 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/datastore/datastore-preferences/maven-metadata.xml | settings |
| WorkManager | 2.12.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/work/work-runtime-ktx/maven-metadata.xml | background packing/export |
| androidx.ink (authoring, brush, geometry, rendering, strokes) | 1.1.0-alpha09 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/ink/ink-authoring/maven-metadata.xml | ink (ADR-002, newest 1.1.0 alpha) |
| androidx.ink ink-nativeloader-jvm (test only) | 1.1.0-alpha09 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/ink/ink-nativeloader-jvm/maven-metadata.xml | host libink.so so core:ink and feature:editor Robolectric tests mesh real strokes (gotchas.md) |
| tracing-ktx (unused, A-003) | 2.0.3 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/tracing/tracing-ktx/maven-metadata.xml | kept for later modules; PerfMonitor uses platform Trace (A-003) |
| Wire (runtime, gradle plugin) | 7.0.4 | Apache-2.0 | https://repo1.maven.org/maven2/com/squareup/wire/wire-runtime/maven-metadata.xml | protobuf .folio payloads |
| commonmark (+ gfm-tables, gfm-strikethrough, task-list-items) | 0.30.0 | BSD-2-Clause | https://repo1.maven.org/maven2/org/commonmark/commonmark/maven-metadata.xml | Markdown parsing |
| jlatexmath-android | 0.2.0 | GPL-2.0 with linking exception (LICENSE of the android branch) | https://repo1.maven.org/maven2/ru/noties/jlatexmath-android/maven-metadata.xml | LaTeX (ADR-007, chosen by P01-S4 over RaTeX; repo archived 2023); +0.69 MB |
| PdfBox-Android | 2.0.27.0 | Apache-2.0 | https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/maven-metadata.xml | PDF export merge (ADR-006); `org.bouncycastle` excluded in core:pdf (P01-S5: unencrypted merge works without it) |
| Coil 3 (coil, coil-compose; no network artifacts) | 3.6.3 | Apache-2.0 | https://repo1.maven.org/maven2/io/coil-kt/coil3/coil/maven-metadata.xml | library thumbnails |
| JUnit4 | 4.13.2 | EPL-1.0 (test-only, never shipped) | https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml | JVM tests (testing rule) |
| Truth | 1.4.5 | Apache-2.0 | https://repo1.maven.org/maven2/com/google/truth/truth/maven-metadata.xml | assertions |
| Turbine | 1.2.1 | Apache-2.0 | https://repo1.maven.org/maven2/app/cash/turbine/turbine/maven-metadata.xml | Flow tests |
| Robolectric | 4.17 | MIT | https://repo1.maven.org/maven2/org/robolectric/robolectric/maven-metadata.xml | JVM Android tests |
| Roborazzi (core, compose, junit-rule, gradle plugin) | 1.75.0 | Apache-2.0 | https://repo1.maven.org/maven2/io/github/takahirom/roborazzi/roborazzi/maven-metadata.xml | screenshot goldens |
| androidx.test (core, runner, rules) | 1.7.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/test/runner/maven-metadata.xml | instrumented tests |
| androidx.test.ext junit | 1.3.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/test/ext/junit/maven-metadata.xml | AndroidJUnit4 |
| uiautomator | 2.4.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/test/uiautomator/uiautomator/maven-metadata.xml | device UI tests |
| benchmark-macro-junit4, baselineprofile plugin | 1.5.0 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/benchmark/benchmark-macro-junit4/maven-metadata.xml | macrobenchmarks (P11) |
| profileinstaller | 1.4.1 | Apache-2.0 | https://dl.google.com/android/maven2/androidx/profileinstaller/profileinstaller/maven-metadata.xml | baseline profile install |
| Spotless gradle plugin | 8.10.3 | Apache-2.0 | https://repo1.maven.org/maven2/com/diffplug/spotless/spotless-plugin-gradle/maven-metadata.xml | formatting gate |
| ktlint | 1.8.0 | MIT | https://repo1.maven.org/maven2/com/pinterest/ktlint/ktlint-cli/maven-metadata.xml | Kotlin style via Spotless |
| detekt (dev.detekt) | 2.0.0-alpha.6 | Apache-2.0 | https://repo1.maven.org/maven2/dev/detekt/detekt-gradle-plugin/maven-metadata.xml | static analysis (alpha: A-001) |
| compose-rules detekt | 0.6.7 | Apache-2.0 | https://repo1.maven.org/maven2/io/nlopez/compose/rules/detekt/maven-metadata.xml | Compose lint rules for detekt |

## Platform differences
- Windows: Claude Code runs shell commands through Git Bash; scripts use LF line endings (.gitattributes). adb is `adb.exe` under `%ANDROID_HOME%\platform-tools`.
- Arch: standard bash; make scripts executable after cloning (README).
