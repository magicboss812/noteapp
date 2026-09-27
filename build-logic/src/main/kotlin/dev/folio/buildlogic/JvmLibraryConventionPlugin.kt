package dev.folio.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = FOLIO_JAVA_VERSION
                targetCompatibility = FOLIO_JAVA_VERSION
            }
            tasks.withType<JavaCompile>().configureEach { options.release.set(FOLIO_JAVA_RELEASE) }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            dependencies {
                add("testImplementation", libs.bundle("unit-test"))
            }
        }
    }
}
