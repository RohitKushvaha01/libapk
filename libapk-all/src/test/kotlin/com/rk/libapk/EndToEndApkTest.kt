package com.rk.libapk

import com.rk.libapk.builder.BuildStage
import com.rk.libapk.model.ActivitySpec
import com.rk.libapk.model.FileResource
import com.rk.libapk.model.LabelValue
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.model.ResourceRef
import com.rk.libapk.model.ResourceSpec
import com.rk.libapk.model.StringResource
import com.rk.libapk.sign.SigningConfig
import com.rk.libapk.testkit.ApkChecks
import com.rk.libapk.testkit.SdkTools
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole pipeline end to end on an APK with native libraries and assets: Java sources compiled
 * with ECJ, dexed with D8, a generated `resources.arsc` with density icons, signed with apksig.
 * Every artifact is then checked with Google's own tools.
 */
class EndToEndApkTest {

    @TempDir
    lateinit var tempDir: Path

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    @Test
    fun `builds a signed apk that aapt2 apksigner zipalign and dexdump all accept`() = runBlocking {
        assumeTrue(SdkTools.available, "Android SDK build-tools not available")

        val androidJar = SdkTools.androidJar
        val packageName = "com.example.app"
        val activityName = "$packageName.MainActivity"

        val sourceDir = tempDir.resolve("src").toFile()
        writeSources(sourceDir, packageName, useAndroidApi = androidJar != null)

        val assetsDir = tempDir.resolve("app_data").toFile().apply { mkdirs() }
        File(assetsDir, "level1.dat").writeBytes(Random(3).nextBytes(2048))
        File(assetsDir, "config.json").writeText("""{"quality":"high"}""")

        val nativeLib = tempDir.resolve("libnative.so").toFile().apply { writeBytes(Random(7).nextBytes(8192)) }
        val mdpiIcon = tempDir.resolve("ic_launcher-mdpi.png").toFile().apply { writeBytes(png) }
        val xxhdpiIcon = tempDir.resolve("ic_launcher-xxhdpi.png").toFile().apply { writeBytes(png) }

        val manifest = ManifestSpec(
            packageName = packageName,
            versionCode = 42,
            versionName = "2.1.0",
            minSdk = 26,
            targetSdk = 34,
            label = LabelValue.Reference(ResourceRef("string", "app_name")),
            icon = ResourceRef("mipmap", "ic_launcher"),
            applicationName = "$packageName.ExampleApplication",
            permissions = listOf("android.permission.INTERNET", "android.permission.VIBRATE"),
            activities = listOf(
                ActivitySpec(name = activityName, launcher = true, screenOrientation = "landscape", exported = true),
            ),
        )

        val resources = ResourceSpec(
            packageName = packageName,
            strings = listOf(
                StringResource("app_name", "Example App"),
                StringResource("app_version", "1.0.0"),
            ),
            files = listOf(
                FileResource("mipmap", "ic_launcher", mdpiIcon, qualifier = "mdpi"),
                FileResource("mipmap", "ic_launcher", xxhdpiIcon, qualifier = "xxhdpi"),
            ),
        )

        val keystore = createKeystore(tempDir.resolve("debug.p12").toFile())

        val apk = tempDir.resolve("example.apk").toFile()
        val stages = mutableListOf<BuildStage>()

        val result = LibApk.builder(
            manifest = manifest,
            signing = keystore,
            classpath = emptyList(),
            platformJars = listOfNotNull(androidJar),
        )
            .resources(resources)
            .sources(sourceDir)
            .nativeLibrary("arm64-v8a", nativeLib)
            .assets(assetsDir)
            .output(apk)
            .build { progress -> stages += progress.stage }

        assertTrue(result.signed, "APK should be signed")
        assertEquals(
            listOf(BuildStage.COMPILE, BuildStage.DEX, BuildStage.RESOURCES, BuildStage.ASSEMBLE, BuildStage.SIGN),
            stages,
        )
        assertTrue(result.entries.contains("AndroidManifest.xml"), "entries: ${result.entries}")
        assertTrue(result.entries.contains("resources.arsc"), "entries: ${result.entries}")
        assertTrue(result.entries.contains("classes.dex"), "entries: ${result.entries}")
        assertTrue(result.entries.contains("lib/arm64-v8a/libnative.so"), "entries: ${result.entries}")
        assertTrue(result.entries.contains("assets/level1.dat"), "entries: ${result.entries}")
        assertTrue(result.entries.contains("assets/config.json"), "entries: ${result.entries}")
        assertTrue(
            result.entries.any { it.startsWith("res/mipmap-xxhdpi/") },
            "density icon missing from entries: ${result.entries}",
        )

        // aapt2 must fully understand the result.
        val badging = ApkChecks.badging(apk)
        println("--- badging ---\n$badging")
        assertTrue(badging.contains("package: name='$packageName'"), "package:\n$badging")
        assertTrue(badging.contains("versionCode='42'"), "versionCode:\n$badging")
        assertTrue(badging.contains("versionName='2.1.0'"), "versionName:\n$badging")
        assertTrue(badging.contains("targetSdkVersion:'34'"), "targetSdk:\n$badging")
        assertTrue(badging.contains("application-label:'Example App'"), "label:\n$badging")
        assertTrue(badging.contains("native-code: 'arm64-v8a'"), "native code:\n$badging")
        assertTrue(badging.contains("launchable-activity: name='$activityName'"), "launcher:\n$badging")
        assertTrue(badging.contains("uses-permission: name='android.permission.INTERNET'"), "permission:\n$badging")

        // Signature and alignment.
        val verification = ApkChecks.verifySignature(apk, minSdk = 26, maxSdk = 34)
        println("--- apksigner ---\n$verification")
        assertTrue(verification.contains("Verifies"), "apksigner rejected the APK:\n$verification")
        assertTrue(
            verification.contains("Verified using v2 scheme (APK Signature Scheme v2): true"),
            "v2 signature missing:\n$verification",
        )

        val alignment = ApkChecks.checkAlignment(apk, alignment = 4, pageAlign = true)
        assertTrue(alignment.ok, "zipalign -c -p 4 failed:\n${alignment.output}")

        // The dex must contain the activity and application classes.
        val dexFile = tempDir.resolve("classes.dex").toFile()
        java.util.zip.ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("classes.dex")).use { input ->
                dexFile.outputStream().use { input.copyTo(it) }
            }
        }
        val dump = SdkTools.dexdump(dexFile.absolutePath)
        assertTrue(dump.contains("L${activityName.replace('.', '/')};"), "activity missing from dex:\n${dump.take(800)}")
    }

    @Test
    fun `build without a signer still produces a well formed unsigned apk`() = runBlocking {
        assumeTrue(SdkTools.available, "Android SDK build-tools not available")

        val packageName = "com.example.app.unsigned"
        val apk = tempDir.resolve("unsigned.apk").toFile()

        val result = LibApk.builder(
            ManifestSpec(
                packageName = packageName,
                label = LabelValue.Literal("Unsigned"),
                minSdk = 26,
                targetSdk = 34,
                activities = listOf(ActivitySpec("$packageName.MainActivity", launcher = true)),
            ),
        )
            .output(apk)
            .build()

        assertTrue(!result.signed)
        assertTrue(result.entries.contains("AndroidManifest.xml"))
        val badging = ApkChecks.badging(apk)
        assertTrue(badging.contains("package: name='$packageName'"), "package:\n$badging")
    }


    private fun writeSources(root: File, packageName: String, useAndroidApi: Boolean) {
        val packageDir = root.resolve(packageName.replace('.', '/'))
        packageDir.mkdirs()

        val activity = if (useAndroidApi) {
            """
            package $packageName;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                private static final String NATIVE_LIB = "libnative";

                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    nativeInit(NATIVE_LIB);
                }

                public String describe() {
                    return "activity for " + NATIVE_LIB;
                }

                private static native void nativeInit(String name);
            }
            """.trimIndent()
        } else {
            """
            package $packageName;

            public class MainActivity {
                public String describe() {
                    return "plain activity stand-in";
                }
            }
            """.trimIndent()
        }
        File(packageDir, "MainActivity.java").writeText(activity)

        File(packageDir, "ExampleApplication.java").writeText(
            """
            package $packageName;

            public class ExampleApplication${if (useAndroidApi) " extends android.app.Application" else ""} {
                public String name() {
                    return "ExampleApplication";
                }
            }
            """.trimIndent(),
        )
    }

    private fun createKeystore(keystore: File): SigningConfig.Keystore {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        SdkTools.run(
            keytool.absolutePath,
            "-genkeypair",
            "-keystore", keystore.absolutePath,
            "-storetype", "PKCS12",
            "-alias", ALIAS,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-storepass", PASSWORD,
            "-keypass", PASSWORD,
            "-dname", "CN=libapk, OU=test, O=libapk, C=US",
        )
        return SigningConfig.Keystore(
            keystore = keystore,
            alias = ALIAS,
            storePassword = PASSWORD.toCharArray(),
            keyPassword = PASSWORD.toCharArray(),
        )
    }

    private companion object {
        const val ALIAS = "libapk"
        const val PASSWORD = "libapk-test"
    }
}
