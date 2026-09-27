package dev.folio.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.util.Properties

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("folio.quality")
            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = libs.versionOf("android-targetSdk").toInt()
                buildFeatures.buildConfig = true
                val keystoreFile = rootProject.file("keystore.properties")
                val releaseSigning =
                    if (keystoreFile.exists()) {
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
            registerVerifyNoInternet()
        }
    }

    private fun Project.registerVerifyNoInternet() {
        val all =
            tasks.register("verifyNoInternet") {
                group = "verification"
                description = "Fails if any variant's merged manifest requests a network permission."
            }
        extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val name = variant.name.replaceFirstChar { it.uppercase() }
                val task =
                    tasks.register<VerifyNoInternetTask>("verifyNoInternet$name") {
                        mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                        report.set(layout.buildDirectory.file("reports/verifyNoInternet/${variant.name}.txt"))
                    }
                all.configure { dependsOn(task) }
            }
        }
    }
}
