package com.rk.libapk.builder

import com.rk.libapk.apk.ApkEntry
import com.rk.libapk.apk.ApkZipWriter
import com.rk.libapk.apk.ZipAlignments
import com.rk.libapk.apk.ZipTimestamp
import com.rk.libapk.compile.Diagnostic
import com.rk.libapk.compile.ICompiler
import com.rk.libapk.dex.DexRequest
import com.rk.libapk.dex.IDexer
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.model.ResourceSpec
import com.rk.libapk.resources.IResourceEncoder
import com.rk.libapk.resources.ResourceRequest
import com.rk.libapk.sign.IApkSigner
import com.rk.libapk.sign.SignRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Stages of a build, in execution order. */
enum class BuildStage { COMPILE, DEX, RESOURCES, ASSEMBLE, SIGN }

/** Progress notification emitted before each stage starts. */
data class BuildProgress(
    val stage: BuildStage,
    val completedStages: Int,
    val totalStages: Int,
    val message: String,
) {
    val fraction: Float get() = if (totalStages == 0) 0f else completedStages.toFloat() / totalStages
}

/** A native library to place at `lib/<abi>/<file name>`. */
data class NativeLibrary(val abi: String, val file: File) {
    init {
        require(abi.isNotBlank()) { "ABI must not be blank" }
        require(file.isFile) { "native library does not exist: $file" }
    }

    val apkPath: String get() = "lib/$abi/${file.name}"
}

/** An asset directory (or single file) copied into `assets/`. */
data class AssetTree(val source: File, val targetPrefix: String = "") {
    init {
        require(source.exists()) { "asset source does not exist: $source" }
        require(!targetPrefix.startsWith("/")) { "asset target prefix must be relative: '$targetPrefix'" }
    }
}

/** Thrown when a build cannot produce an APK. Source errors carry their [Diagnostic]s. */
class ApkBuildException(
    message: String,
    val diagnostics: List<Diagnostic> = emptyList(),
    cause: Throwable? = null,
) : Exception(message, cause)

/** Result of a successful [ApkBuilder.build]. */
data class ApkBuildResult(
    /** The APK that was produced (signed when a signer was configured). */
    val apk: File,
    /** Entry names in the order they were written. */
    val entries: List<String>,
    /** `true` when the APK carries a signature. */
    val signed: Boolean,
    /** Non-fatal messages from the compiler, dexer and signer. */
    val warnings: List<String> = emptyList(),
)

/**
 * Builds an APK from scratch without `aapt2`, the Android SDK or Gradle.
 *
 * Every step is pluggable:
 *
 * | stage | interface | default implementation |
 * |---|---|---|
 * | Java compilation | [ICompiler] | `EcjCompiler` (module `libapk-ecj`) |
 * | dexing | [IDexer] | `D8Dexer` / `R8Dexer` (module `libapk-r8`) |
 * | manifest + resources | [IResourceEncoder] | `ArscResourceEncoder` (module `libapk-resources`) |
 * | signing + alignment | [IApkSigner] | `ApksigSigner` (module `libapk-signing`) |
 *
 * Classes that are compiled elsewhere can be supplied through [dexInputs], which skips the
 * compiler entirely:
 *
 * ```kotlin
 * val result = ApkBuilder(manifest)
 *     .resources(resources)
 *     .dexInputs(appClassesJar)
 *     .nativeLibrary("arm64-v8a", File("libnative.so"))
 *     .assets(File("app_data"))
 *     .dexer(D8Dexer())
 *     .resourceEncoder(ArscResourceEncoder())
 *     .signer(ApksigSigner(signingConfig))
 *     .output(File("app.apk"))
 *     .build { progress -> println("${progress.stage} ${progress.fraction}") }
 * ```
 */
class ApkBuilder(private val manifest: ManifestSpec) {

    private var resourceSpec: ResourceSpec = ResourceSpec(manifest.packageName)
    private var outputFile: File = File("${manifest.packageName}.apk")
    private var unsignedOutputFile: File? = null

    private var sourceFiles: List<File> = emptyList()
    private var classpathFiles: List<File> = emptyList()
    private var libraryFiles: List<File> = emptyList()
    private var dexInputFiles: List<File> = emptyList()
    private var prebuiltDexFiles: List<File> = emptyList()
    private var nativeLibraries: List<NativeLibrary> = emptyList()
    private var assetTrees: List<AssetTree> = emptyList()
    private var extraEntries: List<ApkEntry> = emptyList()

    private var compiler: ICompiler? = null
    private var dexer: IDexer? = null
    private var resourceEncoder: IResourceEncoder? = null
    private var signer: IApkSigner? = null

    private var v1Signing: Boolean = manifest.minSdk < 26
    private var v2Signing: Boolean = true
    private var v3Signing: Boolean = true

    private var minify: Boolean = false
    private var keepRules: List<String> = emptyList()
    private var workDirectory: File? = null
    private var keepWorkDirectory: Boolean = false
    private var buildTimestampMillis: Long? = null


    fun resources(spec: ResourceSpec): ApkBuilder = apply { resourceSpec = spec }

    fun output(file: File): ApkBuilder = apply { outputFile = file }

    /** Where to keep the unsigned APK. Defaults to a temp file that is deleted after signing. */
    fun unsignedOutput(file: File?): ApkBuilder = apply { unsignedOutputFile = file }

    /** Java sources (files or directories) compiled with the configured [ICompiler]. */
    fun sources(vararg files: File): ApkBuilder = apply { sourceFiles = sourceFiles + files }

    fun sources(files: Collection<File>): ApkBuilder = apply { sourceFiles = sourceFiles + files }

    /** Compile classpath (jars or class directories). */
    fun classpath(vararg files: File): ApkBuilder = apply { classpathFiles = classpathFiles + files }

    /**
     * Library jars (`android.jar`). Optional: D8/R8 work without them, but supplying one gives
     * better desugaring and fewer "missing class" warnings when [minify] is enabled.
     */
    fun libraryJars(vararg files: File): ApkBuilder = apply { libraryFiles = libraryFiles + files }

    /** Pre-built `.class` files, class directories, jars or zips to dex. */
    fun dexInputs(vararg files: File): ApkBuilder = apply { dexInputFiles = dexInputFiles + files }

    fun dexInputs(files: Collection<File>): ApkBuilder = apply { dexInputFiles = dexInputFiles + files }

    /** Ready-made `classes.dex` files copied in verbatim (no dexing at all). */
    fun prebuiltDex(vararg files: File): ApkBuilder = apply { prebuiltDexFiles = prebuiltDexFiles + files }

    fun nativeLibrary(abi: String, file: File): ApkBuilder =
        apply { nativeLibraries = nativeLibraries + NativeLibrary(abi, file) }

    fun assets(source: File, targetPrefix: String = ""): ApkBuilder =
        apply { assetTrees = assetTrees + AssetTree(source, targetPrefix) }

    /** Escape hatch: add an arbitrary zip entry (already aligned/compressed as you choose). */
    fun entry(entry: ApkEntry): ApkBuilder = apply { extraEntries = extraEntries + entry }

    fun compiler(compiler: ICompiler?): ApkBuilder = apply { this.compiler = compiler }

    fun dexer(dexer: IDexer?): ApkBuilder = apply { this.dexer = dexer }

    fun resourceEncoder(encoder: IResourceEncoder?): ApkBuilder = apply { this.resourceEncoder = encoder }

    fun signer(signer: IApkSigner?): ApkBuilder = apply { this.signer = signer }

    fun signingSchemes(v1: Boolean? = null, v2: Boolean? = null, v3: Boolean? = null): ApkBuilder = apply {
        v1?.let { v1Signing = it }
        v2?.let { v2Signing = it }
        v3?.let { v3Signing = it }
    }

    fun minify(enabled: Boolean = true, keepRules: List<String> = emptyList()): ApkBuilder = apply {
        minify = enabled
        this.keepRules = this.keepRules + keepRules
    }

    fun workDirectory(directory: File?, keep: Boolean = false): ApkBuilder = apply {
        workDirectory = directory
        keepWorkDirectory = keep
    }

    /** Fixes the zip timestamps, making builds byte-for-byte reproducible. `null` uses the DOS epoch. */
    fun buildTimestamp(epochMillis: Long?): ApkBuilder = apply { buildTimestampMillis = epochMillis }


    suspend fun build(onProgress: (BuildProgress) -> Unit = {}): ApkBuildResult = withContext(Dispatchers.IO) {
        val encoder = resourceEncoder
            ?: throw ApkBuildException("no IResourceEncoder configured (use resourceEncoder(...))")

        val resolvedSources = sourceFiles.flatMap { expandSources(it) }
        val needsCompile = resolvedSources.isNotEmpty()
        val needsDex = needsCompile || dexInputFiles.isNotEmpty()

        if (needsCompile && compiler == null) {
            throw ApkBuildException("${resolvedSources.size} source file(s) were provided but no ICompiler is configured")
        }
        if (needsDex && dexer == null) {
            throw ApkBuildException("classes need dexing but no IDexer is configured (use dexer(...))")
        }

        val stages = buildList {
            if (needsCompile) add(BuildStage.COMPILE)
            if (needsDex) add(BuildStage.DEX)
            add(BuildStage.RESOURCES)
            add(BuildStage.ASSEMBLE)
            if (signer != null) add(BuildStage.SIGN)
        }
        var completed = 0
        val warnings = mutableListOf<String>()
        fun report(stage: BuildStage, message: String) {
            onProgress(BuildProgress(stage, completed, stages.size, message))
        }
        fun stageDone() {
            completed++
        }

        val scratch = workDirectory ?: File(System.getProperty("java.io.tmpdir"), "libapk-build-${System.nanoTime()}")
        scratch.mkdirs()
        val ownScratch = workDirectory == null

        try {
            var compiledClasses: List<File> = emptyList()
            if (needsCompile) {
                report(BuildStage.COMPILE, "compiling ${resolvedSources.size} source file(s)")
                val result = compiler!!.compileJava(resolvedSources)
                warnings += result.diagnostics
                    .filter { it.severity == com.rk.libapk.compile.Severity.WARNING }
                    .map { "${it.source}:${it.line}: ${it.message}" }
                if (!result.isSuccess) {
                    throw ApkBuildException("compilation failed: ${summarize(result.diagnostics)}", result.diagnostics)
                }
                compiledClasses = result.classes.orEmpty()
                stageDone()
            }

            var dexFiles: List<File> = emptyList()
            if (needsDex) {
                report(BuildStage.DEX, "dexing")
                val inputs = buildList {
                    addAll(compiledClasses)
                    addAll(dexInputFiles)
                }
                val request = DexRequest(
                    inputs = inputs,
                    outputDir = File(scratch, "dex"),
                    minApiLevel = manifest.minSdk,
                    classpath = classpathFiles,
                    libraryFiles = libraryFiles,
                    minify = minify,
                    keepRules = effectiveKeepRules(),
                )
                val result = dexer!!.dex(request)
                warnings += result.diagnostics
                if (!result.isSuccess) {
                    throw ApkBuildException(
                        buildString {
                            append(result.errorMessage ?: "dexing failed")
                            if (result.diagnostics.isNotEmpty()) {
                                append(": ")
                                append(result.diagnostics.takeLast(5).joinToString("; "))
                            }
                        },
                    )
                }
                dexFiles = result.dexFiles
                stageDone()
            } else if (prebuiltDexFiles.isNotEmpty()) {
                dexFiles = prebuiltDexFiles
            }

            report(BuildStage.RESOURCES, "encoding manifest and resources.arsc")
            val encoded = encoder.encode(ResourceRequest(manifest, resourceSpec))
            stageDone()

            report(BuildStage.ASSEMBLE, "assembling APK")
            val entries = buildList {
                add(ApkEntry.stored(ENTRY_MANIFEST, encoded.manifestBytes, ZipAlignments.RESOURCES))
                encoded.resourcesArsc?.let {
                    add(ApkEntry.stored(ENTRY_RESOURCES, it, ZipAlignments.RESOURCES))
                }
                addAll(encoded.entries)
                dexFiles.forEach { add(ApkEntry.deflated(it.name, it)) }
                nativeLibraries.forEach { add(ApkEntry.stored(it.apkPath, it.file, ZipAlignments.NATIVE_LIB)) }
                addAll(collectAssetEntries())
                addAll(extraEntries)
            }

            val unsigned = unsignedOutputFile ?: File(scratch, "unsigned.apk")
            ApkZipWriter(timestamp = resolveTimestamp()).write(unsigned, entries)
            stageDone()

            // Signing also aligns stored entries and native library pages.
            val finalApk: File
            if (signer != null) {
                report(BuildStage.SIGN, "signing APK")
                finalApk = signer!!.sign(
                    SignRequest(
                        inputApk = unsigned,
                        outputApk = outputFile,
                        minSdk = manifest.minSdk,
                        enableV1 = v1Signing,
                        enableV2 = v2Signing,
                        enableV3 = v3Signing,
                    ),
                ).outputApk
                stageDone()
                if (unsignedOutputFile == null) unsigned.delete()
            } else {
                outputFile.parentFile?.mkdirs()
                unsigned.copyTo(outputFile, overwrite = true)
                if (unsignedOutputFile == null) unsigned.delete()
                finalApk = outputFile
            }

            ApkBuildResult(
                apk = finalApk,
                entries = entries.map { it.name },
                signed = signer != null,
                warnings = warnings,
            )
        } finally {
            if (ownScratch && !keepWorkDirectory) {
                scratch.deleteRecursively()
            }
        }
    }


    private fun resolveTimestamp(): ZipTimestamp =
        buildTimestampMillis?.let { ZipTimestamp.fromEpochMillis(it) } ?: ZipTimestamp.DOS_EPOCH

    /** Turns compiler diagnostics into a one-line message so failures are actionable. */
    private fun summarize(diagnostics: List<Diagnostic>): String = diagnostics
        .filter { it.severity == com.rk.libapk.compile.Severity.ERROR }
        .take(5)
        .joinToString("; ") { "${it.source}:${it.line}: ${it.message}" }
        .ifEmpty { "no error diagnostics were reported" }

    /** Source directories are expanded to the `.java` files inside them. */
    private fun expandSources(source: File): List<File> = when {
        source.isDirectory -> source.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        else -> listOf(source)
    }

    private fun collectAssetEntries(): List<ApkEntry> = assetTrees.flatMap { tree ->
        val prefix = tree.targetPrefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        if (tree.source.isDirectory) {
            val root = tree.source.toPath()
            tree.source.walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    val relative = root.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    ApkEntry.deflated("assets/$prefix$relative", file)
                }
                .toList()
        } else {
            listOf(ApkEntry.deflated("assets/$prefix${tree.source.name}", tree.source))
        }
    }

    /**
     * R8 removes anything it cannot prove is reachable, so classes Android instantiates from the
     * manifest must be kept explicitly. Native method holders are kept too, for JNI glue.
     */
    private fun effectiveKeepRules(): List<String> {
        if (!minify) return keepRules
        return buildList {
            addAll(keepRules)
            manifest.applicationName?.let { add("-keep class $it { *; }") }
            manifest.activities.forEach { add("-keep class ${it.name} { *; }") }
            add("-keepclasseswithmembernames class * { native <methods>; }")
            // Without android.jar R8 cannot resolve platform classes; do not fail on that.
            if (libraryFiles.isEmpty() && keepRules.none { it.trim().startsWith("-dontwarn") }) {
                add("-dontwarn")
            }
        }
    }

    private companion object {
        const val ENTRY_MANIFEST = "AndroidManifest.xml"
        const val ENTRY_RESOURCES = "resources.arsc"
    }
}
