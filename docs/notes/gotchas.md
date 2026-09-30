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

## androidx.ink in JVM unit tests
The Android ink AARs only carry arm/x86 Android `libink.so`, so Robolectric cannot mesh strokes by default. `androidx.ink:ink-nativeloader-jvm` (same version) ships a linux-x86_64 `libink.so`; as `testImplementation` (core:ink) its `NativeLoader` comes first on the test classpath and real brushes, meshes and `CanvasStrokeRenderer` work under Robolectric (P03-T05). Limit: Robolectric draws meshes as paths, so per-vertex color (OPACITY_MULTIPLIER behaviors, prediction fade) is ignored; texture layers and paint color functions do render. The GitHub mirror (androidx-main) differs from 1.1.0-alpha09; list a jar's real API with a throwaway test that walks `JarFile` entries and reflects constructors via `Class.forName(name, false, loader)` (no natives loaded).

## androidx.ink rotated parallelograms give false hits (1.1.0-alpha09)
`PartitionedMesh.intersects(parallelogram, IDENTITY)` is exact only for rotation 0: `ImmutableParallelogram.fromSegmentAndPadding` of a right-to-left segment (rotation 180) or a vertical one (270) reported hits 4.3 pt beyond the padded area on a 1 pt line (probe test, P03-T08). A box with a rotating `ImmutableAffineTransform` was inconsistent too. `ImmutableTriangle` and `ImmutableBox` with `AffineTransform.IDENTITY` are exact in every direction, so the stroke eraser tests its padded segment rectangle as two triangles (`EraseSession.hitsMesh`). Recheck when ink is upgraded.

## Long sessions are the token cost, not the memory files
Loop logs of P01/P02 (2026-09-29): one session carried all of P02, no auto-compaction ever fired, and the main context grew from 26K to 626K tokens per call. Cache reads were 35M tokens in one P01 run against 178K output; the P02 REVIEW alone re-read ~600K per turn (15.7M over 49 turns). Since then run-loop.sh starts every task in a fresh session and resumes a session only for the task it was working on. Each of the 35 denied Bash commands in those runs also cost a full-context turn.
P03 logs (2026-09-30): Opus with a 1M window auto-compacts only near 967K, so per-task sessions still grew to 300K (T04: 56% of a 5h window; T07: about 80% over three windows). Two causes on top: `claude -p` kills the process 600 s after the last turn while a background subagent runs (a device-tester rerun via SendMessage), which lost the device run and forced a resume at full context; and a resume after a usage-limit wait rewrites the whole cold cache (T07: 290K). run-loop.sh therefore sets `CLAUDE_CODE_AUTO_COMPACT_WINDOW=200000` and `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=3600000`. Check a run for `"subtype":"compact_boundary"` events to confirm compaction fires.

