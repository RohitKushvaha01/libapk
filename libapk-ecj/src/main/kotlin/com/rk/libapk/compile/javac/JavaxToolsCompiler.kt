package com.rk.libapk.compile.javac

import com.rk.libapk.compile.CompileResult
import com.rk.libapk.compile.Diagnostic
import com.rk.libapk.compile.ICompiler
import com.rk.libapk.compile.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/**
 * [ICompiler] backed by the JDK's own compiler (`javax.tools`).
 *
 * JVM-only: `javax.tools` does not exist on Android. Use it on desktops/servers that run a
 * full JDK. For an Android-capable compiler see `EcjCompiler` in the same module.
 *
 * @param outputDir directory that receives the produced `.class` tree. When `null` a
 *   temporary directory is created per compilation.
 * @param options extra `javac` flags (e.g. `-release`, `-classpath`, `-sourcepath`).
 */
class JavaxToolsCompiler(
    private val outputDir: File? = null,
    private val options: List<String> = listOf("-nowarn"),
) : ICompiler {

    override suspend fun compileJava(inputFiles: List<File>): CompileResult = withContext(Dispatchers.IO) {
        if (inputFiles.isEmpty()) {
            return@withContext CompileResult.success(emptyList())
        }

        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
            ?: throw IllegalStateException("JDK is required. Ensure you are not running on a bare JRE.")

        val diagnosticCollector = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnosticCollector, null, null)

        val classesDir = outputDir
            ?: File(System.getProperty("java.io.tmpdir"), "libapk-classes-${System.nanoTime()}")
        classesDir.mkdirs()

        try {
            val compilationUnits = fileManager.getJavaFileObjectsFromFiles(inputFiles)
            val compilerOptions = options + listOf("-d", classesDir.absolutePath)

            val task = compiler.getTask(null, fileManager, diagnosticCollector, compilerOptions, null, compilationUnits)
            val success = task.call()

            val diagnostics = diagnosticCollector.diagnostics.map { native ->
                Diagnostic(
                    line = native.lineNumber.toInt(),
                    col = native.columnNumber.toInt(),
                    source = native.source?.name ?: "unknown",
                    message = native.getMessage(null) ?: "no message",
                    severity = when (native.kind) {
                        javax.tools.Diagnostic.Kind.ERROR -> Severity.ERROR
                        javax.tools.Diagnostic.Kind.WARNING, javax.tools.Diagnostic.Kind.MANDATORY_WARNING -> Severity.WARNING
                        javax.tools.Diagnostic.Kind.NOTE -> Severity.NOTE
                        else -> Severity.OTHER
                    },
                )
            }

            if (success) {
                val classFiles = classesDir.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .toList()
                CompileResult.success(classFiles, diagnostics)
            } else {
                CompileResult.failure(diagnostics)
            }
        } catch (e: Exception) {
            CompileResult.failure(
                listOf(
                    Diagnostic(
                        line = -1,
                        col = -1,
                        source = "compiler",
                        message = e.localizedMessage ?: "unexpected compiler failure",
                        severity = Severity.ERROR,
                    ),
                ),
            )
        } finally {
            fileManager.close()
        }
    }
}
