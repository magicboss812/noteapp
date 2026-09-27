package dev.folio.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.getByType<CommonExtension>().buildFeatures.compose = true
            extensions.configure<ComposeCompilerGradlePluginExtension> {
                stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
            }
            dependencies {
                val bom = platform(libs.lib("compose-bom"))
                add("implementation", bom)
                add("testImplementation", bom)
                add("androidTestImplementation", bom)
                add("implementation", libs.bundle("compose"))
                add("debugImplementation", libs.lib("compose-ui-tooling"))
            }
        }
    }
}
