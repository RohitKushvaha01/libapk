package com.rk.libapk.sign

import com.rk.libapk.apk.ApkEntry
import com.rk.libapk.apk.ApkZipWriter
import com.rk.libapk.apk.ZipAlignments
import com.rk.libapk.model.LabelValue
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.resources.AxmlManifestEncoder
import com.rk.libapk.testkit.ApkChecks
import com.rk.libapk.testkit.SdkTools
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * the signed APK must satisfy Google's own `apksigner verify` for v1, v2 and v3, and must be
 * correctly aligned without a separate zipalign step.
 */
class ApksigSignerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `apksigner verifies v1 v2 and v3 signatures`() = runBlocking {
        assumeTrue(SdkTools.available, "Android SDK build-tools not available")

        val signed = signFreshApk()

        // apksig's verifier only evaluates JAR (v1) signatures when the platform range includes
        // pre-Android-N releases, so ask for the full range to check all three schemes at once.
        val verification = ApkChecks.verifySignature(signed, minSdk = 21, maxSdk = 34)
        println("--- apksigner verify ---\n$verification")

        assertTrue(verification.contains("Verifies"), "apksigner did not verify the APK:\n$verification")
        assertTrue(
            verification.contains("Verified using v1 scheme (JAR signing): true"),
            "v1 signature missing:\n$verification",
        )
        assertTrue(
            verification.contains("Verified using v2 scheme (APK Signature Scheme v2): true"),
            "v2 signature missing:\n$verification",
        )
        assertTrue(
            verification.contains("Verified using v3 scheme (APK Signature Scheme v3): true"),
            "v3 signature missing:\n$verification",
        )
        assertTrue(
            verification.contains("Number of signers: 1"),
            "expected exactly one signer:\n$verification",
        )
    }

    @Test
    fun `zipalign check passes on the signed apk`() = runBlocking {
        assumeTrue(SdkTools.available, "Android SDK build-tools not available")

        val signed = signFreshApk()
        val result = ApkChecks.checkAlignment(signed, alignment = 4, pageAlign = true)
        assertTrue(result.ok, "zipalign -c -p 4 rejected the APK:\n${result.output}")
    }

    @Test
    fun `tampering invalidates the signature`() = runBlocking {
        assumeTrue(SdkTools.available, "Android SDK build-tools not available")

        val signed = signFreshApk()
        assertTrue(ApkChecks.verifySignature(signed).contains("Verifies"))

        // Flip a byte inside the manifest payload: v2/v3 digests must reject it.
        RandomAccessFile(signed, "rw").use { file ->
            file.seek(0x40)
            val original = file.readByte()
            file.seek(0x40)
            file.writeByte(original.toInt().xor(0xFF))
        }

        val verifyTool = SdkTools.requireTool("apksigner")
        val result = SdkTools.runAllowFailure(verifyTool.absolutePath, "verify", signed.absolutePath)
        assertTrue(!result.ok, "tampered APK was still accepted:\n${result.output}")
    }

    private suspend fun signFreshApk(): File {
        val unsigned = tempDir.resolve("unsigned.apk").toFile()
        ApkZipWriter().write(
            unsigned,
            listOf(
                ApkEntry.stored(
                    "AndroidManifest.xml",
                    AxmlManifestEncoder().encode(
                        ManifestSpec(
                            packageName = "com.example.signingtest",
                            label = LabelValue.Literal("Signing Test"),
                            minSdk = 26,
                            targetSdk = 34,
                            debuggable = true,
                        ),
                    ),
                    ZipAlignments.RESOURCES,
                ),
                // A stored native library so page alignment is actually exercised.
                ApkEntry.stored("lib/x86_64/libnative.so", Random(1).nextBytes(4096), ZipAlignments.NATIVE_LIB),
            ),
        )

        val signed = tempDir.resolve("signed.apk").toFile()
        ApksigSigner(keystoreConfig()).sign(
            SignRequest(
                inputApk = unsigned,
                outputApk = signed,
                minSdk = 26,
                enableV1 = true,
                enableV2 = true,
                enableV3 = true,
                debuggable = true,
            ),
        )
        return signed
    }

    private fun keystoreConfig(): SigningConfig.Keystore {
        val keystore = tempDir.resolve("libapk-test.p12").toFile()
        if (!keystore.isFile) {
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
        }
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
