package com.rk.libapk.testkit

import java.io.File

/**
 * Locates and runs the real Android build-tools that ship with an Android SDK.
 *
 * Test-only by design: libapk itself never needs these binaries. Tests use them to *verify*
 * the artifacts libapk produces (aapt2, apksigner, zipalign, dexdump), which is a much stronger
 * check than asserting on our own bytes.
 */
object SdkTools {

    /** Newest installed `build-tools/<version>` directory, if any. */
    val buildToolsDir: File? by lazy {
        sdkDir?.resolve("build-tools")
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.lastOrNull()
    }

    val sdkDir: File? by lazy { locateSdkDir() }

    /** Newest installed platform `android.jar`, if any. Used to compile real Android classes in tests. */
    val androidJar: File? by lazy {
        sdkDir?.resolve("platforms")
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.asReversed()
            ?.firstNotNullOfOrNull { File(it, "android.jar").takeIf { jar -> jar.isFile } }
    }

    val available: Boolean get() = tool("aapt2") != null

    fun tool(name: String): File? = buildToolsDir?.resolve(name)?.takeIf { it.isFile }

    fun requireTool(name: String): File =
        tool(name) ?: error("Android SDK build-tools '$name' not found. Set ANDROID_HOME or sdk.dir in local.properties.")

    /** Runs [command] and returns stdout+stderr. Throws when the process exits non-zero. */
    fun run(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) {
            "command failed (exit $exit): ${command.joinToString(" ")}\n$output"
        }
        return output
    }

    /** Same as [run] but returns the exit code and output without throwing. */
    fun runAllowFailure(vararg command: String): ProcessResult {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    fun aapt2(vararg args: String): String = run(requireTool("aapt2").absolutePath, *args)

    fun apksigner(vararg args: String): String = run(requireTool("apksigner").absolutePath, *args)

    fun zipalign(vararg args: String): ProcessResult =
        runAllowFailure(requireTool("zipalign").absolutePath, *args)

    fun dexdump(vararg args: String): String = run(requireTool("dexdump").absolutePath, *args)

    private fun locateSdkDir(): File? {
        System.getenv("ANDROID_HOME")?.let { path -> File(path).takeIf { it.isDirectory }?.let { return it } }
        System.getenv("ANDROID_SDK_ROOT")?.let { path -> File(path).takeIf { it.isDirectory }?.let { return it } }

        // Walk up from the working directory looking for local.properties with sdk.dir.
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val properties = File(dir, "local.properties")
            if (properties.isFile) {
                val value = properties.readLines()
                    .firstOrNull { it.trim().startsWith("sdk.dir") }
                    ?.substringAfter('=')
                    ?.trim()
                if (!value.isNullOrEmpty()) {
                    File(value).takeIf { it.isDirectory }?.let { return it }
                }
            }
            dir = dir.parentFile
        }
        return null
    }
}

data class ProcessResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Convenience wrappers around the SDK tools used by our integration tests. */
object ApkChecks {

    /** `aapt2 dump badging <apk>`: package name, label, sdk versions, permissions, features. */
    fun badging(apk: File): String = SdkTools.aapt2("dump", "badging", apk.absolutePath)

    /** `aapt2 dump xmltree <apk> --file AndroidManifest.xml`: the decoded manifest tree. */
    fun manifestTree(apk: File, path: String = "AndroidManifest.xml"): String =
        SdkTools.aapt2("dump", "xmltree", apk.absolutePath, "--file", path)

    /** `aapt2 dump resources <apk>`: the resource table as aapt2 sees it. */
    fun resources(apk: File): String = SdkTools.aapt2("dump", "resources", apk.absolutePath)

    /** Throws unless `apksigner verify` accepts the APK for the given SDK range. */
    fun verifySignature(apk: File, minSdk: Int = 26, maxSdk: Int = 34): String =
        SdkTools.apksigner(
            "verify",
            "--min-sdk-version", minSdk.toString(),
            "--max-sdk-version", maxSdk.toString(),
            "--verbose",
            apk.absolutePath,
        )

    /** `zipalign -c -p <alignment>`; returns the raw result so tests can assert on it. */
    fun checkAlignment(apk: File, alignment: Int = 4, pageAlign: Boolean = true): ProcessResult {
        val args = mutableListOf("-c")
        if (pageAlign) args += "-p"
        args += listOf(alignment.toString(), apk.absolutePath)
        return SdkTools.zipalign(*args.toTypedArray())
    }
}
