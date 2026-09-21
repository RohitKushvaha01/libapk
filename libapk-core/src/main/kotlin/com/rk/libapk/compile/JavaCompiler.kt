package com.rk.libapk.compile

import java.io.File

/**
 * Pluggable source compiler.
 *
 * libapk never compiles sources itself: callers either bring their own toolchain or pass
 * pre-built class files. Implementations provided by this project:
 *
 *  * `com.rk.libapk.compile.javac.JavaxToolsCompiler` (JDK `javax.tools`, JVM only)
 *  * `com.rk.libapk.compile.ecj.EcjCompiler` (Eclipse Java Compiler, optional module)
 */
interface ICompiler {

    /**
     * Compiles [inputFiles] and returns the produced `.class` files.
     *
     * Implementations must not throw for *source* errors; they must report them as
     * [Diagnostic]s inside [CompileResult]. Throwing is reserved for environmental
     * failures (missing compiler, unreadable files, ...).
     */
    suspend fun compileJava(inputFiles: List<File>): CompileResult
}

/** Severity of a compiler [Diagnostic]. Mirrors `javax.tools.Diagnostic.Kind`. */
enum class Severity { ERROR, WARNING, NOTE, OTHER }

/** A single compiler message, kept deliberately independent of any compiler API. */
data class Diagnostic(
    val line: Int,
    val col: Int,
    val source: String,
    val message: String,
    val severity: Severity = Severity.ERROR,
)

/** Result of an [ICompiler] run. [classes] is `null` when compilation failed. */
data class CompileResult(
    val classes: List<File>?,
    val diagnostics: List<Diagnostic>,
) {
    /** True when class files were produced. */
    val isSuccess: Boolean get() = classes != null

    /** True when at least one [Severity.ERROR] diagnostic was reported. */
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }

    companion object {
        fun success(classes: List<File>, diagnostics: List<Diagnostic> = emptyList()) =
            CompileResult(classes, diagnostics)

        fun failure(diagnostics: List<Diagnostic>) = CompileResult(null, diagnostics)
    }
}
