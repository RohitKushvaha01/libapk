package com.rk.libapk.dex

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [IDexer] backed by **D8** from Google's R8 project.
 *
 * D8 performs dexing only, with no shrinking or obfuscation, so every class passed in survives
 * verbatim.
 *
 * It needs **no Android SDK**: `android.jar` is not required (D8 emits warnings for the language
 * features it cannot desugar without a library, but still produces a valid `classes.dex`).
 *
 * R8's own classes are Java 11 bytecode, so the process running the build needs a Java 11+ runtime.
 */
class D8Dexer : IDexer {
    override suspend fun dex(request: DexRequest): DexResult = R8Dexers.run(request, minify = false)
}

/**
 * [IDexer] backed by **R8**: dexing plus tree shaking, minification and (optionally) obfuscation.
 *
 * Because R8 removes classes it believes are unreachable, [DexRequest.keepRules] must cover
 * everything Android instantiates reflectively (activities, services and the application class
 * named in the manifest). `ApkBuilder` adds those automatically.
 */
class R8Dexer : IDexer {
    override suspend fun dex(request: DexRequest): DexResult = R8Dexers.run(request, minify = true)
}

internal object R8Dexers {

    suspend fun run(request: DexRequest, minify: Boolean): DexResult = withContext(Dispatchers.IO) {
        if (request.inputs.isEmpty()) {
            return@withContext DexResult.failure("no dex inputs were provided")
        }

        val programFiles = request.inputs
            .flatMap { expand(it) }
            .map { it.toPath() }
        if (programFiles.isEmpty()) {
            return@withContext DexResult.failure("no class files or archives found in ${request.inputs}")
        }

        val missing = request.inputs.filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            return@withContext DexResult.failure("dex inputs do not exist: ${missing.joinToString { it.path }}")
        }

        val diagnostics = CollectingDiagnostics()
        val mode = if (request.release) CompilationMode.RELEASE else CompilationMode.DEBUG
        val outputZip = request.outputZip

        val output = outputZip?.also { it.parentFile?.mkdirs() }?.toPath()
            ?: request.outputDir.also { it.mkdirs() }.toPath()

        try {
            if (minify) {
                val builder = R8Command.builder(diagnostics)
                    .addProgramFiles(programFiles)
                    .setMinApiLevel(request.minApiLevel)
                    .setMode(mode)
                    .setOutput(output, OutputMode.DexIndexed)
                    .setDisableTreeShaking(false)
                    .setDisableMinification(false)

                applyClasspath(builder, request)
                request.libraryFiles.takeIf { it.isNotEmpty() }
                    ?.let { builder.addLibraryFiles(it.map(File::toPath)) }

                val rules = buildList {
                    addAll(request.keepRules)
                    // Tree shaking needs the platform classes to reason about reachability. Without
                    // a library jar (no android.jar / JDK classes) R8 treats every missing class as
                    // an error, so downgrade those to warnings unless the caller already decided.
                    if (request.libraryFiles.isEmpty() &&
                        request.keepRules.none { it.trim().startsWith("-dontwarn") }
                    ) {
                        add("-dontwarn")
                    }
                }
                if (rules.isNotEmpty()) {
                    builder.addProguardConfiguration(rules, Origin.unknown())
                }
                request.keepRulesFiles.forEach { builder.addProguardConfigurationFile(it.toPath(), Origin.unknown()) }

                R8.run(builder.build())
            } else {
                val builder = D8Command.builder(diagnostics)
                    .addProgramFiles(programFiles)
                    .setMinApiLevel(request.minApiLevel)
                    .setMode(mode)
                    .setOutput(output, OutputMode.DexIndexed)
                    .setDisableDesugaring(!request.enableDesugaring)

                applyClasspath(builder, request)
                request.libraryFiles.takeIf { it.isNotEmpty() }
                    ?.let { builder.addLibraryFiles(it.map(File::toPath)) }

                D8.run(builder.build())
            }
        } catch (e: Exception) {
            return@withContext DexResult.failure(
                e.message ?: e.toString(),
                diagnostics.messages.toList(),
            )
        }

        val dexFiles = if (outputZip != null) {
            listOf(outputZip)
        } else {
            request.outputDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("dex", ignoreCase = true) }
                .sortedBy { it.name }
                .toList()
        }

        if (dexFiles.isEmpty()) {
            DexResult.failure("dexer produced no output", diagnostics.messages.toList())
        } else {
            DexResult.success(dexFiles, diagnostics.messages.toList())
        }
    }

    private fun applyClasspath(builder: com.android.tools.r8.BaseCommand.Builder<*, *>, request: DexRequest) {
        if (request.classpath.isNotEmpty()) {
            builder.addClasspathFiles(request.classpath.map(File::toPath))
        }
    }

    /**
     * D8 accepts `.class`/`.jar`/`.zip` files but not directories, so directories are expanded
     * recursively. This keeps `DexRequest.inputs` pleasant to use (point it at a build output dir).
     */
    private fun expand(input: File): List<File> = when {
        !input.isDirectory -> listOf(input)
        else -> input.walkTopDown()
            .filter { it.isFile && (it.extension == "class" || it.extension == "jar" || it.extension == "zip") }
            .toList()
    }

    private class CollectingDiagnostics : DiagnosticsHandler {
        val messages = CopyOnWriteArrayList<String>()

        override fun error(diagnostic: Diagnostic) {
            messages += "error: ${diagnostic.diagnosticMessage}"
        }

        override fun warning(diagnostic: Diagnostic) {
            messages += "warning: ${diagnostic.diagnosticMessage}"
        }

        override fun info(diagnostic: Diagnostic) {
            messages += "info: ${diagnostic.diagnosticMessage}"
        }
    }
}
