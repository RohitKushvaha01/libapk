package com.rk.libapk.sign

import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Pluggable APK signer.
 *
 * Signing is mandatory for an installable APK, and the default implementation
 * (`com.rk.libapk.sign.ApksigSigner`, module `libapk-signing`) uses Google's official apksig.
 * Besides producing v1/v2/v3 signatures it also performs the 4-byte and 16384-byte page
 * alignment that Android expects, so no `zipalign` step is needed.
 */
interface IApkSigner {
    suspend fun sign(request: SignRequest): SignResult
}

/** Everything a signer needs for one APK. */
data class SignRequest(
    val inputApk: File,
    val outputApk: File,
    /** Drives which signature schemes are legal and which alignment page size is used. */
    val minSdk: Int,
    val enableV1: Boolean = true,
    val enableV2: Boolean = true,
    val enableV3: Boolean = true,
    /** v4 produces a separate `.idsig` file next to the APK; off by default. */
    val enableV4: Boolean = false,
    /** When `true` the signer leaves the zip layout untouched (disables alignment). */
    val alignmentPreserved: Boolean = false,
    /** `true` allows signing an APK whose manifest is missing or debuggable. */
    val debuggable: Boolean = true,
)

/** Result of an [IApkSigner] run. */
data class SignResult(
    val outputApk: File,
    val warnings: List<String> = emptyList(),
)

/** Where the signing key comes from. */
sealed interface SigningConfig {

    /** A JKS/PKCS12 keystore on disk (what `keytool` produces). */
    class Keystore(
        val keystore: File,
        val alias: String,
        val storePassword: CharArray,
        /** Defaults to [storePassword] when `null`. */
        val keyPassword: CharArray? = null,
        /** `null` auto-detects PKCS12 vs JKS from the file header. */
        val storeType: String? = null,
    ) : SigningConfig

    /**
     * A key that is already in memory.
     *
     * Useful when key material is generated or embedded rather than shipped as a keystore file.
     */
    class InMemory(
        val privateKey: PrivateKey,
        val certificates: List<X509Certificate>,
        val name: String = "libapk",
    ) : SigningConfig {
        init {
            require(certificates.isNotEmpty()) { "at least one certificate is required" }
        }
    }
}
