package com.rk.libapk.dex

import java.io.File

/**
 * Pluggable `.class` -> `.dex` converter.
 *
 * The default implementation (`com.rk.libapk.dex.D8Dexer`, module `libapk-r8`) is backed
 * by Google's R8/D8, which needs no Android SDK: no `android.jar` is required for plain
 * dexing. Consumers that ship their own dexer (or a pre-built `classes.dex`) can implement
 * this interface instead.
 */
interface IDexer {

    /**
     * Dexes [DexRequest.inputs] (`.class` files, directories, jars or zips) into
     * [DexRequest.outputDir].
     *
     * Implementations must not throw for *dex* errors; report them via [DexResult.diagnostics]
     * and/or [DexResult.errorMessage]. Throwing is reserved for environmental failures.
     */
    suspend fun dex(request: DexRequest): DexResult
}

/**
 * Everything a dexer needs. Defaults are tuned for the "no Android SDK" use case:
 * desugaring stays enabled (D8 warns instead of failing when no `android.jar` is given)
 * and [libraryFiles] is empty.
 */
data class DexRequest(
    /** Inputs: `.class` files, class directories, `.jar`/`.zip` archives. */
    val inputs: List<File>,
    /** Directory that receives `classes.dex` (and `classes2.dex`, ... if needed). */
    val outputDir: File,
    /** Android API level used for desugaring decisions and default-interface handling. */
    val minApiLevel: Int = 26,
    /** Program classpath (jars/classes the input references). */
    val classpath: List<File> = emptyList(),
    /** Library/`android.jar` files. Optional; D8/R8 work without them. */
    val libraryFiles: List<File> = emptyList(),
    /** `true` for `CompilationMode.RELEASE`, `false` for `DEBUG`. */
    val release: Boolean = true,
    /** Let D8 rewrite language features for older API levels. */
    val enableDesugaring: Boolean = true,
    /** `true` runs full R8 tree shaking + minification; `false` is D8-only dexing. */
    val minify: Boolean = false,
    /** Inline ProGuard/R8 keep rules. */
    val keepRules: List<String> = emptyList(),
    /** Files containing ProGuard/R8 keep rules. */
    val keepRulesFiles: List<File> = emptyList(),
    /** Optional zip to write instead of a directory of `.dex` files. */
    val outputZip: File? = null,
)

/** Result of an [IDexer] run. */
data class DexResult(
    val dexFiles: List<File>,
    val diagnostics: List<String> = emptyList(),
    val errorMessage: String? = null,
) {
    val isSuccess: Boolean get() = errorMessage == null && dexFiles.isNotEmpty()

    companion object {
        fun success(dexFiles: List<File>, diagnostics: List<String> = emptyList()) =
            DexResult(dexFiles, diagnostics)

        fun failure(message: String, diagnostics: List<String> = emptyList()) =
            DexResult(emptyList(), diagnostics, message)
    }
}
