package dev.folio.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Spotless (ktlint), detekt (+ Compose rules) and a per-module `qa` task.
 * Root `./gradlew qa` runs every module's `qa` plus the root checks (build.gradle.kts).
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.diffplug.spotless")
            pluginManager.apply("dev.detekt")
            val ktlintVersion = libs.versionOf("ktlint")
            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("src/**/*.kt")
                    ktlint(ktlintVersion)
                }
                kotlinGradle {
                    target("*.gradle.kts")
                    ktlint(ktlintVersion)
                }
            }
            extensions.configure<DetektExtension> {
                buildUponDefaultConfig.set(true)
                parallel.set(true)
                config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
            }
            dependencies {
                add("detektPlugins", libs.lib("compose-rules-detekt"))
            }
            val qa =
                tasks.register("qa") {
                    group = "verification"
                    description = "Module quality gate: spotless, detekt, lint, unit and screenshot tests."
                    dependsOn("spotlessCheck", "detekt")
                }
            listOf("com.android.application", "com.android.library").forEach { id ->
                pluginManager.withPlugin(id) { qa.configure { dependsOn("lintDebug", "testDebugUnitTest") } }
            }
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { qa.configure { dependsOn("test") } }
            pluginManager.withPlugin("io.github.takahirom.roborazzi") {
                qa.configure { dependsOn("verifyRoborazziDebug") }
            }
        }
    }
}
