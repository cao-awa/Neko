package com.github.kusa233.neko

import com.github.kusa233.neko.source.SourceFile
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `System.currentTimeMillis()`, `Math.abs(x)`, `Integer.parseInt(s)` — a JDK class named as the
 * receiver of a static member.
 *
 * This is worth a test of its own because the language has neither static-import syntax nor qualified
 * type syntax: the only spelling available is `Cls.member`, which means the *class name* has to be
 * recognised as a receiver. It was not, for any JDK class, and the reader was told the name did not
 * exist — although `System` is exactly the class they meant and no other spelling would have worked.
 *
 * Both halves are asserted: that the program compiles, and that it prints what the JDK methods
 * actually return. A descriptor the JVM rejects compiles cleanly and fails at load time, so a
 * compile-only test would have missed the interesting failure.
 */
class PlatformStaticTest {

    @Test
    fun `a static method of a JDK class is called by class name`() {
        val program = TestSupport.file(
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            v: long = System.currentTimeMillis()",
            "            println(v > 0)",
            "            d: double = Math.abs(-3.5)",
            "            println(d)",
            "            i: int = Integer.parseInt(\"42\")",
            "            println(i + 1)",
            "        }",
            "    }",
            "}",
        )

        assertEquals(
            emptyList(),
            TestSupport.errors(TestSupport.compile(program)).map { it.message },
            "a JDK static call should compile",
        )
        assertEquals(listOf("true", "3.5", "43"), run(program))
    }

    @Test
    fun `a static field of a JDK class is read by class name`() {
        // `System.out` is the one every program meets, because `println` is modelled on it. It is a
        // field rather than a method, so it exercises the other branch of the same resolution.
        val program = TestSupport.file(
            "class Uses {",
            "    static {",
            "        fun f(): Object {",
            "            out: Object = System.out",
            "            return out",
            "        }",
            "    }",
            "}",
        )

        assertEquals(
            emptyList(),
            TestSupport.errors(TestSupport.compile(program)).map { it.message },
            "a JDK static field should compile",
        )
    }

    @Test
    fun `a call with no matching overload names the overloads that exist`() {
        // Overload selection is by arity and by whether each argument fits the reflected parameter.
        // Neko has no implicit numeric widening in arguments, so a mismatch has to be reported rather
        // than guessed at: a guess would emit a descriptor the JVM rejects at run time.
        val result = TestSupport.compile(
            TestSupport.file(
                "class Uses {",
                "    static {",
                "        fun f(): long {",
                "            return System.currentTimeMillis(true)",
                "        }",
                "    }",
                "}",
            ),
        )

        val message = TestSupport.errors(result).single().message
        assertTrue(
            "System.currentTimeMillis" in message && "()" in message,
            "the message should name the method and what it declares, but was: $message",
        )
    }

    @Test
    fun `a JDK class with no such static member says so`() {
        val result = TestSupport.compile(
            TestSupport.file(
                "class Uses {",
                "    static {",
                "        fun f(): long {",
                "            return System.noSuchMethod()",
                "        }",
                "    }",
                "}",
            ),
        )

        assertEquals(
            listOf("System has no static member named 'noSuchMethod'"),
            TestSupport.errors(result).map { it.message },
        )
    }

    @Test
    fun `a static member of a class from an imported package is reached by simple name`() {
        // `java.lang` is searched without an import, but everything else has to be named through one —
        // and that path went through a different branch, which only asked `java.lang`. `Objects` is not
        // there, so `Objects.requireNonNull(...)` was reported as an unresolved *name* even though the
        // file said `import java.util`. The class is what the reader meant, and the import is what made
        // it reachable, so the two together have to resolve.
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            println(Objects.equals(\"a\", \"a\"))",
            "            println(Objects.toString(\"b\"))",
            "        }",
            "    }",
            "}",
        )

        assertEquals(
            emptyList(),
            TestSupport.errors(TestSupport.compile(program)).map { it.message },
            "an imported JDK class should resolve as a static-call receiver",
        )
    }

    @Test
    fun `a static field of a class from an imported package is read by simple name`() {
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun f(): Object {",
            "            empty: Object = Collections.EMPTY_LIST",
            "            return empty",
            "        }",
            "    }",
            "}",
        )

        assertEquals(
            emptyList(),
            TestSupport.errors(TestSupport.compile(program)).map { it.message },
            "an imported JDK class should resolve as a static-field receiver",
        )
    }

    @Test
    fun `an imported class is also nameable in a type position`() {
        // The same lookup backs type positions, so the two readings of `Objects` cannot disagree:
        // naming it in a signature has to work wherever calling its members does.
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun f(a: Objects) {",
            "            println(a)",
            "        }",
            "    }",
            "}",
        )

        assertEquals(
            emptyList(),
            TestSupport.errors(TestSupport.compile(program)).map { it.message },
            "an imported JDK class should resolve in a type position",
        )
    }

    /** Compiles [program], runs its `main()`, and returns what it printed, one line per entry. */
    private fun run(program: String): List<String> {
        val directory = Files.createTempDirectory("neko-platform")
        val result = Compiler.compileProgram(
            listOf(SourceFile.of("Uses.neko", program)),
            outputDirectory = directory,
        )
        check(!result.hasErrors) {
            "expected the program to compile, but it reported:\n" +
                result.diagnostics.joinToString("\n") { it.toString() }
        }

        val loader = URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader)
        val type = Class.forName("com.github.kusa233.test.Uses", true, loader)
        // A Neko `main()` with no parameters is emitted alongside the synthetic `main(String[])` the
        // launcher uses, so the arity is what picks this one out.
        val main = type.declaredMethods.single { it.name == "main" && it.parameterCount == 0 }

        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            main.invoke(null)
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .trim()
            .lines()
    }
}
