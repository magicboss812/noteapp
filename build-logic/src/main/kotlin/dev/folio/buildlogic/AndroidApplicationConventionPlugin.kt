package dev.folio.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = libs.versionOf("android-targetSdk").toInt()
                buildFeatures.buildConfig = true
                val keystoreFile = rootProject.file("keystore.properties")
                val releaseSigning = if (keystoreFile.exists()) {
                    val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
                    signingConfigs.create("release") {
                        storeFile = rootProject.file(props.getProperty("storeFile"))
                        storePassword = props.getProperty("storePassword")
                        keyAlias = props.getProperty("keyAlias")
                        keyPassword = props.getProperty("keyPassword")
                    }
                } else {
                    null
                }
                buildTypes.getByName("debug") {
                    applicationIdSuffix = ".debug"
                }
                buildTypes.getByName("release") {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    signingConfig = releaseSigning
                }
            }
        }
    }
}
