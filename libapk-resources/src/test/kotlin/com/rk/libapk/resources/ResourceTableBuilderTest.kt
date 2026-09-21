package com.rk.libapk.resources

import com.rk.libapk.apk.ApkEntry
import com.rk.libapk.apk.ApkZipWriter
import com.rk.libapk.apk.ZipAlignments
import com.rk.libapk.model.ActivitySpec
import com.rk.libapk.model.FileResource
import com.rk.libapk.model.LabelValue
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.model.ResourceRef
import com.rk.libapk.model.ResourceSpec
import com.rk.libapk.model.StringResource
import com.rk.libapk.testkit.ApkChecks
import com.rk.libapk.testkit.SdkTools
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertTrue

/**
 * a real `resources.arsc` plus `res/` payload, referenced from the binary manifest, must be
 * understood by aapt2, including density-specific icons.
 */
class ResourceTableBuilderTest {

    @TempDir
    lateinit var tempDir: Path

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    @Test
    fun `string and mipmap resources resolve as references in the manifest`() {
        assumeTrue(SdkTools.available, "Android SDK build-tools not available")

        val packageName = "com.example.app"
        val mdpiIcon = tempDir.resolve("ic_launcher-mdpi.png").toFile().apply { writeBytes(png) }
        val xxhdpiIcon = tempDir.resolve("ic_launcher-xxhdpi.png").toFile().apply { writeBytes(png) }

        val resourceSpec = ResourceSpec(
            packageName = packageName,
            strings = listOf(StringResource("app_name", "Resource App")),
            files = listOf(
                FileResource("mipmap", "ic_launcher", mdpiIcon, qualifier = "mdpi"),
                FileResource("mipmap", "ic_launcher", xxhdpiIcon, qualifier = "xxhdpi"),
            ),
        )
        val resources = ResourceTableBuilder().build(resourceSpec)

        val manifest = ManifestSpec(
            packageName = packageName,
            label = LabelValue.Reference(ResourceRef("string", "app_name")),
            icon = ResourceRef("mipmap", "ic_launcher"),
            activities = listOf(ActivitySpec("com.example.app.MainActivity", launcher = true)),
        )
        val axml = AxmlManifestEncoder().encode(manifest, resources.ids)

        val apk = tempDir.resolve("resources.apk").toFile()
        ApkZipWriter().write(
            apk,
            buildList {
                add(ApkEntry.stored("AndroidManifest.xml", axml, ZipAlignments.RESOURCES))
                add(ApkEntry.stored("resources.arsc", resources.arscBytes, ZipAlignments.RESOURCES))
                addAll(resources.entries)
            },
        )

        val badging = ApkChecks.badging(apk)
        println("--- badging ---\n$badging")

        assertTrue(badging.contains("application-label:'Resource App'"), "label from string resource missing:\n$badging")
        assertTrue(
            badging.contains("application-icon-160:'res/mipmap-mdpi/ic_launcher.png'"),
            "mdpi icon missing:\n$badging",
        )
        assertTrue(
            badging.contains("application-icon-480:'res/mipmap-xxhdpi/ic_launcher.png'"),
            "xxhdpi icon missing:\n$badging",
        )
        assertTrue(
            badging.contains("launchable-activity: name='com.example.app.MainActivity'"),
            "launcher activity missing:\n$badging",
        )

        val dump = ApkChecks.resources(apk)
        println("--- resources ---\n$dump")
        assertTrue(dump.contains("string/app_name"), "string resource missing from table:\n$dump")
        assertTrue(dump.contains("mipmap/ic_launcher"), "mipmap resource missing from table:\n$dump")
    }

    @Test
    fun `assigns stable ids per type and name and shares them across qualifiers`() {
        val mdpi = tempDir.resolve("a.png").toFile().apply { writeBytes(png) }
        val hdpi = tempDir.resolve("b.png").toFile().apply { writeBytes(png) }

        val result = ResourceTableBuilder().build(
            ResourceSpec(
                packageName = "com.example.app",
                strings = listOf(StringResource("app_name", "App")),
                files = listOf(
                    FileResource("mipmap", "ic_launcher", mdpi, qualifier = "mdpi"),
                    FileResource("mipmap", "ic_launcher", hdpi, qualifier = "hdpi"),
                ),
            ),
        )

        val labelId = result.ids.idOf(ResourceRef("string", "app_name"))
        val iconId = result.ids.idOf(ResourceRef("mipmap", "ic_launcher"))
        assertTrue(labelId != null && labelId != 0, "string id was not assigned")
        assertTrue(iconId != null && iconId != 0, "mipmap id was not assigned")
        assertTrue(labelId != iconId, "different resources must get different ids")

        // Both density variants are entries in the APK...
        assertTrue(result.entries.any { it.name == "res/mipmap-mdpi/ic_launcher.png" })
        assertTrue(result.entries.any { it.name == "res/mipmap-hdpi/ic_launcher.png" })
    }

    @Test
    fun `unresolved references fail with a clear message`() {
        val manifest = ManifestSpec(
            packageName = "com.example.app",
            icon = ResourceRef("mipmap", "missing_icon"),
        )
        val error = runCatching { AxmlManifestEncoder().encode(manifest) }.exceptionOrNull()
        assertTrue(error is IllegalStateException, "expected IllegalStateException, got $error")
        assertTrue(
            error.message!!.contains("@mipmap/missing_icon"),
            "error should name the missing resource: ${error.message}",
        )
    }
}
