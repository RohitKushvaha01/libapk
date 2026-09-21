package com.rk.libapk.resources

import com.rk.libapk.apk.ApkEntry
import com.rk.libapk.apk.ApkZipWriter
import com.rk.libapk.apk.ZipAlignments
import com.rk.libapk.model.ActivitySpec
import com.rk.libapk.model.AttributeValueSpec
import com.rk.libapk.model.LabelValue
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.model.MetaDataSpec
import com.rk.libapk.model.UsesFeatureSpec
import com.rk.libapk.testkit.ApkChecks
import com.rk.libapk.testkit.SdkTools
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * the manifest libapk writes must be accepted and understood by Google's own `aapt2`,
 * which is the strongest available check short of installing the APK.
 */
class AxmlManifestEncoderTest {

    @TempDir
    lateinit var tempDir: Path

    private val manifestSpec = ManifestSpec(
        packageName = "com.example.app",
        versionCode = 7,
        versionName = "1.2.3",
        minSdk = 26,
        targetSdk = 34,
        compileSdk = 34,
        label = LabelValue.Literal("My App"),
        debuggable = false,
        extractNativeLibs = false,
        permissions = listOf("android.permission.INTERNET"),
        features = listOf(UsesFeatureSpec("android.hardware.vulkan.version", required = true)),
        activities = listOf(
            ActivitySpec(
                name = "com.example.app.MainActivity",
                launcher = true,
                screenOrientation = "landscape",
            ),
        ),
        applicationMetaData = listOf(
            MetaDataSpec("com.example.app.version", AttributeValueSpec.Text("libapk")),
        ),
    )

    @Test
    fun `aapt2 dump badging understands the generated manifest`() {
        assumeTrue(SdkTools.available, "Android SDK build-tools not available")

        val apk = buildManifestOnlyApk()

        val badging = ApkChecks.badging(apk)
        println("--- aapt2 dump badging ---\n$badging")

        assertTrue(badging.contains("package: name='com.example.app'"), "package name missing:\n$badging")
        assertTrue(badging.contains("versionCode='7'"), "versionCode missing:\n$badging")
        assertTrue(badging.contains("versionName='1.2.3'"), "versionName missing:\n$badging")
        // aapt2 prints `sdkVersion` unless compileSdkVersion is present, then `minSdkVersion`.
        assertTrue(
            badging.contains("sdkVersion:'26'") || badging.contains("minSdkVersion:'26'"),
            "minSdk missing:\n$badging",
        )
        assertTrue(badging.contains("targetSdkVersion:'34'"), "targetSdk missing:\n$badging")
        // aapt2 prints `application-label:` for a label resource, but folds a literal label into
        // the `application:` line.
        assertTrue(
            badging.contains("application-label:'My App'") || badging.contains("application: label='My App'"),
            "label missing:\n$badging",
        )
        assertTrue(
            badging.contains("uses-permission: name='android.permission.INTERNET'"),
            "permission missing:\n$badging",
        )
        assertTrue(
            badging.contains("launchable-activity: name='com.example.app.MainActivity'"),
            "launcher activity missing:\n$badging",
        )
        assertTrue(
            badging.contains("uses-feature: name='android.hardware.vulkan.version'"),
            "uses-feature missing:\n$badging",
        )
    }

    @Test
    fun `aapt2 xmltree decodes the nested structures`() {
        assumeTrue(SdkTools.available, "Android SDK build-tools not available")

        val apk = buildManifestOnlyApk()
        val tree = ApkChecks.manifestTree(apk)
        println("--- aapt2 dump xmltree ---\n$tree")

        // Attribute ids must be real framework ids, not 0x00000000 placeholders.
        assertTrue(tree.contains("android:screenOrientation"), "screenOrientation missing:\n$tree")
        assertTrue(tree.contains("landscape"), "screenOrientation value missing:\n$tree")
        assertTrue(tree.contains("meta-data"), "meta-data missing:\n$tree")
        assertTrue(tree.contains("com.example.app.version"), "meta-data name missing:\n$tree")
        assertTrue(tree.contains("intent-filter"), "intent-filter missing:\n$tree")
        assertTrue(tree.contains("android.intent.action.MAIN"), "MAIN action missing:\n$tree")
        assertTrue(tree.contains("android.intent.category.LAUNCHER"), "LAUNCHER category missing:\n$tree")
        assertTrue(tree.contains("uses-sdk"), "uses-sdk missing:\n$tree")
    }

    private fun buildManifestOnlyApk(): File {
        val axml = AxmlManifestEncoder().encode(manifestSpec)
        val apk = tempDir.resolve("manifest-only.apk").toFile()
        ApkZipWriter().write(
            apk,
            listOf(ApkEntry.stored("AndroidManifest.xml", axml, ZipAlignments.RESOURCES)),
        )
        return apk
    }
}
