package com.github.kusa233.neko

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `--run <class>` compiles a program and then executes the named class' `main`.
 *
 * The two halves are tested separately because they fail differently: the command line has to keep the
 * class name instead of reading it as a source path, and the runner has to find the class among the
 * bytes this compilation produced rather than among class files that may or may not be on disk.
 */
class CliRunTest {

    // ------------------------------------------------------------------ command line

    @Test
    fun `parses the class named after --run`() {
        val command = CommandLine.parse(
            arrayOf("Program.neko", "--run", "neko.sample.TestClass"),
        ) ?: error("expected a parsed command line")

        assertEquals("neko.sample.TestClass", command.runClass)
        assertEquals(1, command.sources.size, "the class named after --run is not a source path")
    }

    @Test
    fun `parses --run before the sources`() {
        val command = CommandLine.parse(
            arrayOf("--run", "neko.sample.TestClass", "Program.neko"),
        ) ?: error("expected a parsed command line")

        assertEquals("neko.sample.TestClass", command.runClass)
        assertEquals("Program.neko", command.sources.single().fileName.toString())
    }

    @Test
    fun `a command line without --run has no run target`() {
        val command = CommandLine.parse(arrayOf("Program.neko")) ?: error("expected a command line")

        assertNull(command.runClass)
    }

    @Test
    fun `--run without a class name is refused`() {
        val failure = assertFailsWith<CommandLineException> {
            CommandLine.parse(arrayOf("Program.neko", "--run"))
        }

        assertTrue(failure.message.orEmpty().contains("requires a class name"))
    }

    @Test
    fun `--run with a blank class name is refused`() {
        assertFailsWith<CommandLineException> {
            CommandLine.parse(arrayOf("Program.neko", "--run", "   "))
        }
    }

    // ------------------------------------------------------------------ running

    @Test
    fun `runs the main of a class named in dot form`() {
        val result = compile(
            "class Greeter() {",
            "    static {",
            "        fun main() {",
            "            println(\"hello from neko\")",
            "        }",
            "    }",
            "}",
        )

        val outcome: ProgramRunner.Outcome
        val output: String
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            outcome = ProgramRunner.run(result.classes, "com.github.kusa233.test.Greeter")
        } finally {
            System.setOut(original)
        }
        assertEquals(ProgramRunner.Outcome.Returned, outcome)
        assertEquals("hello from neko\n", buffer.toString(Charsets.UTF_8).replace("\r\n", "\n"))
    }

    @Test
    fun `reports a class the compilation did not produce`() {
        val result = compile(
            "class Greeter() {",
            "    static {",
            "        fun main() {",
            "            println(\"hello\")",
            "        }",
            "    }",
            "}",
        )

        assertEquals(
            ProgramRunner.Outcome.MissingClass("neko.sample.Absent"),
            ProgramRunner.run(result.classes, "neko.sample.Absent"),
        )
    }

    @Test
    fun `reports a class with no main`() {
        val result = compile(
            "class Helpers() {",
            "    static {",
            "        fun greet() {",
            "            println(\"hi\")",
            "        }",
            "    }",
            "}",
        )

        assertEquals(
            ProgramRunner.Outcome.MissingMain("com.github.kusa233.test.Helpers"),
            ProgramRunner.run(result.classes, "com.github.kusa233.test.Helpers"),
        )
    }

    @Test
    fun `surfaces the exception main threw`() {
        val result = compile(
            "class Boom() {",
            "    static {",
            "        fun main() {",
            "            println(boom(0))",
            "        }",
            "",
            "        fun boom(d: int): int {",
            "            return 1 / d",
            "        }",
            "    }",
            "}",
        )

        val outcome = ProgramRunner.run(result.classes, "com.github.kusa233.test.Boom")
        val failure = (outcome as ProgramRunner.Outcome.Failed).cause
        assertTrue(
            failure is ArithmeticException,
            "expected main's own exception, but got $failure",
        )
    }

    @Test
    fun `prefers the compiled class over one on the compiler's own classpath`() {
        // The compiler ships classes under `com.github.kusa233.neko` too. A program is allowed to
        // declare one of those names, and the runner must execute the class just compiled rather than
        // the compiler's same-named class — which has no `main` at all.
        val result = compileSource(
            """
            locate com.github.kusa233.neko

            class Compiler {
                static {
                    fun main() {
                        println("from neko")
                    }
                }
            }
            """.trimIndent(),
        )

        val outcome: ProgramRunner.Outcome
        val output: String
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            outcome = ProgramRunner.run(result.classes, "com.github.kusa233.neko.Compiler")
        } finally {
            System.setOut(original)
        }
        assertEquals(ProgramRunner.Outcome.Returned, outcome)
        assertEquals("from neko\n", buffer.toString(Charsets.UTF_8).replace("\r\n", "\n"))
    }

    private fun compile(vararg lines: String): CompilationResult =
        compileSource(TestSupport.file(*lines))

    private fun compileSource(source: String): CompilationResult {
        val result = TestSupport.compile(source, name = "Run.neko")
        check(!result.hasErrors) {
            "expected the program to compile, but it reported:\n" +
                result.diagnostics.joinToString("\n") { it.toString() }
        }
        return result
    }
}
