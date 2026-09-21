package com.rk.libapk.dex

import com.rk.libapk.testkit.SdkTools
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertTrue

/** R8/D8 must turn real compiled classes into a `classes.dex` that Android's own `dexdump` accepts. */
class D8DexerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dexes a directory of classes into a dexdump-valid classes dex`() = runBlocking {
        val classesDir = compileHello()

        val result = D8Dexer().dex(
            DexRequest(
                inputs = listOf(classesDir),
                outputDir = tempDir.resolve("dex").toFile(),
                minApiLevel = 26,
            ),
        )

        assertTrue(
            result.isSuccess,
            "dexing failed: ${result.errorMessage}\n${result.diagnostics.joinToString("\n")}",
        )

        val dex = result.dexFiles.single { it.name == "classes.dex" }
        assertTrue(dex.length() > 0, "classes.dex is empty")

        if (SdkTools.available) {
            val dump = SdkTools.dexdump(dex.absolutePath)
            assertTrue(
                dump.contains("Lcom/example/Hello;"),
                "Hello class missing from dex dump:\n${dump.take(1000)}",
            )
            assertTrue(
                dump.contains("greet"),
                "method missing from dex dump:\n${dump.take(1000)}",
            )
        }
    }

    @Test
    fun `r8 minification keeps manifest-referenced classes when told to`() = runBlocking {
        val classesDir = compileHello()

        val result = R8Dexer().dex(
            DexRequest(
                inputs = listOf(classesDir),
                outputDir = tempDir.resolve("r8-dex").toFile(),
                minApiLevel = 26,
                minify = true,
                keepRules = listOf("-keep class com.example.Hello { *; }"),
            ),
        )

        assertTrue(
            result.isSuccess,
            "r8 dexing failed: ${result.errorMessage}\n${result.diagnostics.joinToString("\n")}",
        )
        val dex = result.dexFiles.single { it.name == "classes.dex" }
        if (SdkTools.available) {
            val dump = SdkTools.dexdump(dex.absolutePath)
            assertTrue(dump.contains("Lcom/example/Hello;"), "kept class missing:\n${dump.take(1000)}")
        }
    }

    @Test
    fun `reports a clear failure for missing inputs`() = runBlocking {
        val result = D8Dexer().dex(
            DexRequest(inputs = emptyList(), outputDir = tempDir.resolve("none").toFile()),
        )
        assertTrue(!result.isSuccess)
        assertTrue(result.errorMessage!!.isNotBlank())
    }

    private fun compileHello(): File {
        val sourceDir = tempDir.resolve("src/com/example").toFile().apply { mkdirs() }
        File(sourceDir, "Hello.java").writeText(
            """
            package com.example;

            public class Hello {
                public String greet(String name) {
                    return "hello " + name;
                }
            }
            """.trimIndent(),
        )
        val classesDir = tempDir.resolve("classes").toFile().apply { mkdirs() }

        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("a JDK is required to run these tests")
        val exit = compiler.run(
            null,
            null,
            null,
            "-d", classesDir.absolutePath,
            "--release", "11",
            File(sourceDir, "Hello.java").absolutePath,
        )
        check(exit == 0) { "javac failed with exit code $exit" }
        return classesDir
    }
}
