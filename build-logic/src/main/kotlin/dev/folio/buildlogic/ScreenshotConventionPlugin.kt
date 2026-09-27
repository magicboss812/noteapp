package dev.folio.buildlogic

import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Robolectric + Roborazzi screenshot tests. Goldens live in `src/test/screenshots` (committed);
 * `recordRoborazziDebug` writes them, `verifyRoborazziDebug` (part of qa) compares.
 * Each module also needs `src/test/resources/robolectric.properties` with `sdk=36` (docs/notes/gotchas.md).
 */
class ScreenshotConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.github.takahirom.roborazzi")
            extensions.configure<RoborazziExtension> {
                outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
            }
            tasks.withType<Test>().configureEach {
                systemProperty("robolectric.graphicsMode", "NATIVE")
                systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // Robolectric (SDK 36) reflects into FileDescriptor internals; JDK 21 blocks that unless opened.
                jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
            dependencies {
                add("testImplementation", libs.bundle("robolectric"))
                add("testImplementation", libs.lib("roborazzi"))
                add("testImplementation", libs.lib("roborazzi-compose"))
                add("testImplementation", libs.lib("roborazzi-junit-rule"))
                add("testImplementation", libs.lib("compose-ui-test-junit4"))
                add("debugImplementation", libs.lib("compose-ui-test-manifest"))
            }
        }
    }
}
