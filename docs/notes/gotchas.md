# Gotchas
Longer explanations moved out of CLAUDE.md `## Learned` or rules. One section per topic, newest last. Keep < 200 lines.

## connectedAndroidTest can report BUILD SUCCESSFUL without running tests
When the APK install fails (e.g. HyperOS `INSTALL_FAILED_USER_RESTRICTED`), `connectedDebugAndroidTest` still ends with BUILD SUCCESSFUL. Judge instrumented runs by `<module>/build/outputs/androidTest-results/connected/**/TEST-*.xml` (test count > 0, no failures), never by the Gradle status alone.

## Robolectric on JDK 21 needs module flags
Robolectric (SDK 36 sandbox) reflects into `java.io.FileDescriptor` via `jdk.internal.access`; without `--add-opens java.base/java.io` and `--add-exports java.base/jdk.internal.access` it fails with "Failed to interact with raw FileDescriptor internals". Since P02-T07 `configureAndroidCommon` (build-logic) sets both for every Android module's unit tests.

## Robolectric SDK pin per module
Robolectric 4.17 supports up to SDK 36 while targetSdk is 37. Every module with Robolectric tests (screenshot or not: :app, core:text, core:pdf, core:storage, feature:library) needs `src/test/resources/robolectric.properties` with `sdk=36`; otherwise tests fail with `NoSuchMethodException: InputManager.getInstance()`.

## Detekt 2.0 config keys
Detekt 2.0 renamed thresholds: `TooManyFunctions` uses `allowedFunctionsPer{Class,Interface,File,Object,Enum}` (not `thresholdIn*`); an unknown key fails every detekt task. Also: detekt run in the same Gradle call as `spotlessApply` may report line numbers of the pre-format file; rerun detekt alone before fixing.

## APK size comparisons need clean builds
Incremental debug packaging keeps entries of removed dependencies: switching a library to `compileOnly` changed the debug APK by only kilobytes. Compare sizes only after `./gradlew :app:clean :app:assembleDebug` (P01-S4: RaTeX +3.54 MB arm64 measured this way).

## AGP 9: extra source directories via the variant API
`android.sourceSets.getByName("androidTest").assets.srcDir(...)` fails in AGP 9 with a ClassCastException (old `AndroidLibrarySourceSet` type). Use `androidComponents { onVariants { it.androidTest?.sources?.assets?.addStaticSourceDirectory(path) } }` (used for the math corpus in commit b8f3c31, removed with the probe in P01-T08).

## Host has no unzip
Neither `unzip` nor `python` may be used here; inspect AAR/JAR sources on GitHub (raw.githubusercontent.com) or let Gradle resolve the artifact instead.

## Long sessions are the token cost, not the memory files
Loop logs of P01/P02 (2026-09-29): one session carried all of P02, no auto-compaction ever fired, and the main context grew from 26K to 626K tokens per call. Cache reads were 35M tokens in one P01 run against 178K output; the P02 REVIEW alone re-read ~600K per turn (15.7M over 49 turns). Since then run-loop.sh starts every task in a fresh session and resumes a session only for the task it was working on. Each of the 35 denied Bash commands in those runs also cost a full-context turn.

