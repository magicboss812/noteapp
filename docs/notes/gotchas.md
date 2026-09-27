# Gotchas
Longer explanations moved out of CLAUDE.md `## Learned` or rules. One section per topic, newest last. Keep < 200 lines.

## connectedAndroidTest can report BUILD SUCCESSFUL without running tests
When the APK install fails (e.g. HyperOS `INSTALL_FAILED_USER_RESTRICTED`), `connectedDebugAndroidTest` still ends with BUILD SUCCESSFUL. Judge instrumented runs by `<module>/build/outputs/androidTest-results/connected/**/TEST-*.xml` (test count > 0, no failures), never by the Gradle status alone.

## Robolectric on JDK 21 needs module flags
Robolectric (SDK 36 sandbox) reflects into `java.io.FileDescriptor` via `jdk.internal.access`; without `--add-opens java.base/java.io` and `--add-exports java.base/jdk.internal.access` it fails with "Failed to interact with raw FileDescriptor internals". `folio.screenshot` sets both.

## Robolectric SDK pin per module
Robolectric 4.17 supports up to SDK 36 while targetSdk is 37. Every module that applies `folio.screenshot` needs `src/test/resources/robolectric.properties` with `sdk=36` (see :app) until Robolectric supports 37.

