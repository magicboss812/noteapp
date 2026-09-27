# Gotchas
Longer explanations moved out of CLAUDE.md `## Learned` or rules. One section per topic, newest last. Keep < 200 lines.

## connectedAndroidTest can report BUILD SUCCESSFUL without running tests
When the APK install fails (e.g. HyperOS `INSTALL_FAILED_USER_RESTRICTED`), `connectedDebugAndroidTest` still ends with BUILD SUCCESSFUL. Judge instrumented runs by `<module>/build/outputs/androidTest-results/connected/**/TEST-*.xml` (test count > 0, no failures), never by the Gradle status alone.

## Robolectric on JDK 21 needs module flags
Robolectric (SDK 36 sandbox) reflects into `java.io.FileDescriptor` via `jdk.internal.access`; without `--add-opens java.base/java.io` and `--add-exports java.base/jdk.internal.access` it fails with "Failed to interact with raw FileDescriptor internals". `folio.screenshot` sets both.

## Robolectric SDK pin per module
Robolectric 4.17 supports up to SDK 36 while targetSdk is 37. Every module that applies `folio.screenshot` needs `src/test/resources/robolectric.properties` with `sdk=36` (see :app) until Robolectric supports 37.

## APK size comparisons need clean builds
Incremental debug packaging keeps entries of removed dependencies: switching a library to `compileOnly` changed the debug APK by only kilobytes. Compare sizes only after `./gradlew :app:clean :app:assembleDebug` (P01-S4: RaTeX +3.54 MB arm64 measured this way).

## AGP 9: extra source directories via the variant API
`android.sourceSets.getByName("androidTest").assets.srcDir(...)` fails in AGP 9 with a ClassCastException (old `AndroidLibrarySourceSet` type). Use `androidComponents { onVariants { it.androidTest?.sources?.assets?.addStaticSourceDirectory(path) } }` (see core/text/build.gradle.kts).

## Host has no unzip
Neither `unzip` nor `python` may be used here; inspect AAR/JAR sources on GitHub (raw.githubusercontent.com) or let Gradle resolve the artifact instead.

