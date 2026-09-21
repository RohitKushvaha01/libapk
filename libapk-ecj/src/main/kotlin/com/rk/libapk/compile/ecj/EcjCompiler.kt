package com.rk.libapk.compile.ecj

import com.rk.libapk.compile.CompileResult
import com.rk.libapk.compile.Diagnostic
import com.rk.libapk.compile.ICompiler
import com.rk.libapk.compile.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jdt.internal.compiler.batch.Main
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * [ICompiler] backed by the **Eclipse Java Compiler (ECJ)**.
 *
 * ECJ is a pure-Java compiler: no `javac`, no JDK toolchain requirement beyond running the library
 * (ECJ 3.46 needs a Java 17 runtime). It is used by on-device Android IDEs for exactly this reason.
 *
 * Structured problems are read from ECJ's XML `-log` output, with the human-readable output as a
 * fallback, so callers get line/column diagnostics rather than a wall of text.
 *
 * @param outputDir directory that receives the `.class` tree; a temp directory per run when `null`.
 * @param release `--release` value (e.g. `"11"`, `"17"`). `null` compiles against the running JDK.
 *   Ignored when [bootclasspath] is given, because `--release` and `-bootclasspath` are exclusive.
 * @param classpath compile classpath (jars or class directories).
 * @param bootclasspath platform classes to compile against. Pass `android.jar` here: it contains
 *   `java.lang.*` stubs, so it must be a boot classpath, not a regular classpath entry.
 * @param sourceTarget `-source`/`-target` used together with [bootclasspath].
 * @param sourcePath optional source path for looking up referenced sources.
 * @param encoding source encoding.
 * @param extraOptions raw ECJ flags appended before the input files.
 */
class EcjCompiler(
    private val outputDir: File? = null,
    private val release: String? = DEFAULT_RELEASE,
    private val classpath: List<File> = emptyList(),
    private val bootclasspath: List<File> = emptyList(),
    private val sourceTarget: String = DEFAULT_SOURCE_TARGET,
    private val sourcePath: List<File> = emptyList(),
    private val encoding: String = "UTF-8",
    private val extraOptions: List<String> = emptyList(),
) : ICompiler {

    override suspend fun compileJava(inputFiles: List<File>): CompileResult = withContext(Dispatchers.IO) {
        if (inputFiles.isEmpty()) {
            return@withContext CompileResult.success(emptyList())
        }

        val classesDir = outputDir
            ?: File(System.getProperty("java.io.tmpdir"), "libapk-ecj-classes-${System.nanoTime()}")
        classesDir.mkdirs()

        val logFile = File.createTempFile("libapk-ecj-", ".xml")
        val standardOut = StringWriter()
        val standardErr = StringWriter()

        val arguments = buildList {
            add("-d"); add(classesDir.absolutePath)
            add("-encoding"); add(encoding)
            // No annotation processing: there is no processor path to configure.
            add("-proc:none")
            add("-log"); add(logFile.absolutePath)
            if (bootclasspath.isNotEmpty()) {
                // A platform jar (android.jar) carries java.lang.* stubs: it must replace the JDK's
                // system modules rather than join the classpath, and `--release` cannot be combined
                // with an explicit boot classpath.
                add("-bootclasspath"); add(bootclasspath.joinToString(File.pathSeparator) { it.absolutePath })
                add("-source"); add(sourceTarget)
                add("-target"); add(sourceTarget)
            } else {
                release?.let { add("--release"); add(it) }
            }
            if (classpath.isNotEmpty()) {
                add("-classpath"); add(classpath.joinToString(File.pathSeparator) { it.absolutePath })
            }
            if (sourcePath.isNotEmpty()) {
                add("-sourcepath"); add(sourcePath.joinToString(File.pathSeparator) { it.absolutePath })
            }
            addAll(extraOptions)
            addAll(inputFiles.map { it.absolutePath })
        }

        val succeeded = try {
            // The 4-arg constructor (with an empty option map) is the non-deprecated entry point.
            Main(PrintWriter(standardOut), PrintWriter(standardErr), false, HashMap()).compile(arguments.toTypedArray())
        } catch (e: Exception) {
            standardErr.write(e.toString())
            false
        }

        val diagnostics = EcjProblemParser.parse(logFile, standardErr.toString())
        logFile.delete()

        if (succeeded) {
            val classFiles = classesDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .toList()
            CompileResult.success(classFiles, diagnostics)
        } else {
            val reported = diagnostics.ifEmpty {
                listOf(
                    Diagnostic(
                        line = -1,
                        col = -1,
                        source = "ecj",
                        message = standardErr.toString().ifBlank { "compilation failed" }.trim(),
                        severity = Severity.ERROR,
                    ),
                )
            }
            CompileResult.failure(reported)
        }
    }

    companion object {
        /** Android-compatible bytecode that D8 can dex without extra desugaring work. */
        const val DEFAULT_RELEASE = "11"

        /**
         * `-source`/`-target` used when a platform boot classpath such as `android.jar` is given.
         *
         * Java 8 bytecode is the standard Android configuration: ECJ rejects `-bootclasspath` at
         * compliance 9 and above (it insists on `--release` there), and D8 desugars Java 8 input
         * without complaint. Raise it via [EcjCompiler]'s `sourceTarget` if you know your toolchain
         * supports it.
         */
        const val DEFAULT_SOURCE_TARGET = "1.8"
    }
}

/** Parses ECJ's XML `-log` output, falling back to its human-readable diagnostics. */
internal object EcjProblemParser {

    private val problemTag = Regex(
        "<problem\\b([^>]*)>(.*?)</problem>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val attribute = Regex("([A-Za-z]+)=\"([^\"]*)\"")
    private val messageValue = Regex("<message\\b[^>]*\\bvalue=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val sourceValue = Regex("<source\\b[^>]*\\bvalue=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val textProblem = Regex("^\\s*\\d+\\.\\s+(ERROR|WARNING|INFO)\\s+in\\s+(.+?)\\s+\\(at line (\\d+)\\)\\s*$")

    fun parse(logFile: File, stderr: String): List<Diagnostic> {
        val fromXml = if (logFile.isFile) parseXml(logFile.readText(Charsets.UTF_8)) else emptyList()
        return fromXml.ifEmpty { parseText(stderr) }
    }

    private fun parseXml(xml: String): List<Diagnostic> {
        val result = ArrayList<Diagnostic>()
        for (match in problemTag.findAll(xml)) {
            val attributes = attribute.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val body = match.groupValues[2]
            result += Diagnostic(
                line = attributes["line"]?.toIntOrNull() ?: -1,
                col = attributes["charstart"]?.toIntOrNull() ?: -1,
                source = sourceValue.find(body)?.groupValues?.get(1)?.let(::unescape) ?: "unknown",
                message = messageValue.find(body)?.groupValues?.get(1)?.let(::unescape)
                    ?: attributes["message"]?.let(::unescape)
                    ?: "compilation problem",
                severity = severityOf(attributes["severity"]),
            )
        }
        return result
    }

    private fun parseText(stderr: String): List<Diagnostic> = stderr.lineSequence()
        .mapNotNull { line ->
            val match = textProblem.find(line) ?: return@mapNotNull null
            Diagnostic(
                line = match.groupValues[3].toIntOrNull() ?: -1,
                col = -1,
                source = match.groupValues[2].trim(),
                message = match.groupValues[1].lowercase(),
                severity = severityOf(match.groupValues[1]),
            )
        }
        .toList()

    private fun severityOf(value: String?): Severity = when (value?.lowercase()) {
        "error" -> Severity.ERROR
        "warning" -> Severity.WARNING
        "info" -> Severity.NOTE
        else -> Severity.OTHER
    }

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
