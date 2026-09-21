package com.rk.libapk.sign

import com.android.apksig.ApkSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * [IApkSigner] backed by Google's official `com.android.tools.build:apksig`.
 *
 * Two things make this more than a thin wrapper:
 *
 *  * **Alignment comes for free.** With [SignRequest.alignmentPreserved] left `false`, apksig
 *    rewrites stored entries so they are 4-byte aligned and uncompressed `.so` files are aligned
 *    to a 16384-byte page, the same contract `zipalign -p 4` provides. libapk aligns while
 *    writing too, so both the signed and the unsigned archive are correct.
 *  * **No Android SDK.** apksig is a pure Java library with zero dependencies; it needs API 24+
 *    (v1 signing / source stamping need API 26), which matches libapk's minSdk 26.
 */
class ApksigSigner(private val config: SigningConfig) : IApkSigner {

    override suspend fun sign(request: SignRequest): SignResult = withContext(Dispatchers.IO) {
        require(request.inputApk.isFile) { "input APK does not exist: ${request.inputApk}" }
        request.outputApk.parentFile?.mkdirs()

        ApkSigner.Builder(listOf(createSignerConfig(config)))
            .setInputApk(request.inputApk)
            .setOutputApk(request.outputApk)
            .setMinSdkVersion(request.minSdk)
            .setV1SigningEnabled(request.enableV1)
            .setV2SigningEnabled(request.enableV2)
            .setV3SigningEnabled(request.enableV3)
            .setV4SigningEnabled(request.enableV4)
            .setAlignmentPreserved(request.alignmentPreserved)
            .setDebuggableApkPermitted(request.debuggable)
            .setOtherSignersSignaturesPreserved(false)
            .build()
            .sign()

        SignResult(request.outputApk)
    }

    private fun createSignerConfig(config: SigningConfig): ApkSigner.SignerConfig =
        when (config) {
            is SigningConfig.InMemory -> ApkSigner.SignerConfig.Builder(
                config.name,
                config.privateKey,
                config.certificates,
            ).build()

            is SigningConfig.Keystore -> {
                val storeType = config.storeType ?: detectStoreType(config.keystore)
                val keyStore = KeyStore.getInstance(storeType)
                config.keystore.inputStream().use { keyStore.load(it, config.storePassword) }

                val key = keyStore.getKey(config.alias, config.keyPassword ?: config.storePassword)
                require(key is PrivateKey) {
                    "alias '${config.alias}' in ${config.keystore} does not hold a private key"
                }

                val chain = keyStore.getCertificateChain(config.alias)
                    ?: error("alias '${config.alias}' in ${config.keystore} has no certificate chain")
                val certificates = chain.map { it as X509Certificate }

                ApkSigner.SignerConfig.Builder(config.alias, key, certificates).build()
            }
        }

    /** JKS files start with the magic `FE ED FE ED`; PKCS12 is DER and starts with `30 82`. */
    private fun detectStoreType(keystore: File): String {
        val header = ByteArray(4)
        val read = keystore.inputStream().use { it.read(header) }
        val isJks = read == 4 &&
            header[0] == 0xFE.toByte() && header[1] == 0xED.toByte() &&
            header[2] == 0xFE.toByte() && header[3] == 0xED.toByte()
        return if (isJks) "JKS" else "PKCS12"
    }
}
