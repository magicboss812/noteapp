package dev.folio.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory

/** Fails when a merged app manifest requests a network permission (CLAUDE.md: offline). */
abstract class VerifyNoInternetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(mergedManifest.get().asFile)
        val requested =
            PERMISSION_TAGS.flatMap { tag ->
                val nodes = doc.getElementsByTagName(tag)
                (0 until nodes.length).map {
                    nodes
                        .item(it)
                        .attributes
                        .getNamedItemNS(ANDROID_NS, "name")
                        .nodeValue
                }
            }
        val forbidden = requested.filter { it in FORBIDDEN }
        if (forbidden.isNotEmpty()) {
            throw GradleException("Network permission(s) in ${mergedManifest.get().asFile}: $forbidden")
        }
        report.get().asFile.writeText("OK: ${requested.sorted().joinToString()}\n")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")
        val FORBIDDEN =
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.CHANGE_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE",
                "android.permission.CHANGE_WIFI_STATE",
            )
    }
}
