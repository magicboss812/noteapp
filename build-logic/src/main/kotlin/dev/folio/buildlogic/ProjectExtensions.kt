package dev.folio.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

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
    }
    dependencies {
        add("testImplementation", libs.bundle("unit-test"))
    }
}
