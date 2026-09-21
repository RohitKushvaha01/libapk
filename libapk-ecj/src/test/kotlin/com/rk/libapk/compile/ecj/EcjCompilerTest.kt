package com.rk.libapk.compile.ecj

import com.rk.libapk.compile.Severity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ECJ is the optional Java compiler; it must produce classes and structured diagnostics. */
class EcjCompilerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `compiles java sources to class files`() = runBlocking {
        val source = writeSource(
            "com/example/Hello.java",
            """
            package com.example;

            public class Hello {
                public String greet(String name) {
                    return "hello " + name;
                }
            }
            """,
        )

        val classesDir = tempDir.resolve("classes").toFile()
        val result = EcjCompiler(outputDir = classesDir).compileJava(listOf(source))

        assertTrue(
            result.isSuccess,
            "compilation failed: ${result.diagnostics.joinToString("\n") { "${it.source}:${it.line} ${it.message}" }}",
        )
        assertTrue(
            result.classes!!.any { it.name == "Hello.class" },
            "Hello.class not produced, got ${result.classes!!.map { it.name }}",
        )
        assertTrue(result.hasErrors.not(), "unexpected errors: ${result.diagnostics}")
    }

    @Test
    fun `reports errors with line numbers and does not produce classes`() = runBlocking {
        val source = writeSource(
            "com/example/Broken.java",
            """
            package com.example;

            public class Broken {
                public void run() {
                    int value = "not an int";
                }
            }
            """,
        )

        val result = EcjCompiler(outputDir = tempDir.resolve("broken-classes").toFile())
            .compileJava(listOf(source))

        assertFalse(result.isSuccess, "compilation should have failed")
        val errors = result.diagnostics.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isNotEmpty(), "no ERROR diagnostic reported: ${result.diagnostics}")
        assertTrue(
            errors.any { it.line == 5 },
            "expected an error on line 5, got ${errors.map { it.line to it.message }}",
        )
        assertTrue(result.hasErrors)
    }

    @Test
    fun `empty input is a successful no-op`() = runBlocking {
        val result = EcjCompiler().compileJava(emptyList())
        assertTrue(result.isSuccess)
        assertEquals(0, result.classes!!.size)
    }

    private fun writeSource(relativePath: String, content: String): File {
        val file = tempDir.resolve("src/$relativePath").toFile()
        file.parentFile.mkdirs()
        file.writeText(content.trimIndent())
        return file
    }
}
