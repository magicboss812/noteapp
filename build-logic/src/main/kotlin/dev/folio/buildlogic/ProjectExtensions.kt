package dev.folio.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/** Bytecode level for every module. Built with JDK 21 without a toolchain (A-002). */
internal val FOLIO_JAVA_VERSION = JavaVersion.VERSION_17
internal const val FOLIO_JAVA_RELEASE = 17

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.versionOf(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

internal fun VersionCatalog.bundle(alias: String) = findBundle(alias).get()

internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.apply {
        compileSdk = libs.versionOf("android-compileSdk").toInt()
        compileSdkMinor = libs.versionOf("android-compileSdkMinor").toInt()
        buildToolsVersion = libs.versionOf("android-buildTools")
        defaultConfig.apply {
            minSdk = libs.versionOf("android-minSdk").toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions.apply {
            sourceCompatibility = FOLIO_JAVA_VERSION
            targetCompatibility = FOLIO_JAVA_VERSION
        }
        testOptions.apply {
            unitTests.isIncludeAndroidResources = true
        }
        lint.apply {
            abortOnError = true
            lintConfig = rootProject.file("config/lint/lint.xml")
            error += setOf("NewApi", "MissingPermission", "WrongThread")
        }
    }
    tasks.withType<Test>().configureEach {
        // Robolectric (SDK 36) reflects into FileDescriptor internals; JDK 21 blocks that unless opened.
        jvmArgs(
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        )
    }
    dependencies {
        add("testImplementation", libs.bundle("unit-test"))
    }
}
