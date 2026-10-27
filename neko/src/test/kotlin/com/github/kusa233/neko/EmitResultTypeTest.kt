package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the typed half of an `emit` clause: `emit [int, int] -> int`.
 *
 * The values in the brackets are what a `scratch` hands to the handler. The `-> R` half is what the
 * handler promises back, and writing it changes two things:
 *
 *  * a `scratch` inside a function declaring the clause is worth exactly `R`, so the site does not
 *    have to repeat the type;
 *  * a `nyan` in the matching `try` must produce an `R`. A *bare* `nyan` — or no `nyan` at all — stays
 *    legal and answers `null`, which the site reads as `R`'s Java default.
 *
 * Without `-> R` the older, untyped agreement holds: the handler answers an `Object` and each site
 * converts it to whatever it asked for. That is what makes one handler able to serve an `int` site
 * and a `string` site at once, and the last test here pins that it still can.
 */
class EmitResultTypeTest {

    private fun program(vararg lines: String): String = buildString {
        append("locate com.github.kusa233.neko.sample\n")
        append('\n')
        for (line in lines) {
            append(line)
            append('\n')
        }
    }

    private fun run(loaded: TestSupport.LoadedProgram): String =
        loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }

    private fun errorsOf(source: String): List<String> =
        TestSupport.errors(TestSupport.compile(source, name = "Emit.neko")).map { it.message }

    /** The site takes the declared result without repeating the type in a variable declaration. */
    @Test
    fun `a scratch is worth the result the clause declares`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1, 2",
                "            return v",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            r: int = try Emitter.source() { a, b -> nyan a + b }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("3\n", run(loaded))
    }

    /**
     * A bare `nyan` is still legal with a declared result, and the site takes the type's default.
     *
     * This is the "or leave it empty" half of the request: writing `-> int` says what a *valued*
     * `nyan` must produce, not that every handler must produce one.
     */
    @Test
    fun `a bare nyan with a declared result gives the site the default`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            r: int = try Emitter.source() { a -> nyan }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("0\n", run(loaded))
    }

    /** A handler that never `nyan`s behaves the same way: the body falls off its end. */
    @Test
    fun `a handler that writes no nyan gives the site the default`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            r: int = try Emitter.source() { a ->",
                "                println(a)",
                "            }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("1\n0\n", run(loaded))
    }

    /** A result type other than `int`, so the conversion is not just the integer path. */
    @Test
    fun `the declared result may be a reference type`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            s: string = scratch 7",
                "            println(s)",
                "            return 0",
                "        } emit [int] -> string",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            r: int = try Emitter.source() { n -> nyan \"n=\" + n }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("n=7\n0\n", run(loaded))
    }

    /** The promise is checked: a `nyan` producing something else is refused, and says what to write. */
    @Test
    fun `a nyan that contradicts the declared result is refused`() {
        val messages = errorsOf(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int] -> int",
                "",
                "        fun run() {",
                "            r: int = try source() { a -> nyan \"not an int\" }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            messages.any { "this 'nyan' answers string, but the 'emit' clause promises int" in it },
            "expected the promise to be enforced, but got:\n${messages.joinToString("\n")}",
        )
    }

    /**
     * A site whose position wants something else is refused once, not twice.
     *
     * The declared result is what the site is worth, so a position asking for another type disagrees
     * with the clause rather than with the handler. Reporting the assignment as well would describe
     * the same mistake a second time.
     */
    @Test
    fun `a scratch whose position disagrees with the declared result is refused once`() {
        val messages = errorsOf(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            s: string = scratch 1",
                "            return 0",
                "        } emit [int] -> int",
                "    }",
                "}",
            ),
        )
        assertTrue(
            messages.any { "this 'scratch' answers int" in it && "wants string" in it },
            "expected the disagreement to be named, but got:\n${messages.joinToString("\n")}",
        )
        assertEquals(
            1,
            messages.size,
            "one mistake should be reported once, but got:\n${messages.joinToString("\n")}",
        )
    }

    /** A clause with no values may still declare a result: `emit [] -> int`. */
    @Test
    fun `a clause with no values may declare a result`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun ping(): int {",
                "            v: int = scratch",
                "            return v",
                "        } emit [] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            r: int = try Emitter.ping() { -> nyan 42 }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("42\n", run(loaded))
    }

    /**
     * Without `-> R` nothing is promised, and one handler may still answer different types.
     *
     * This is the shape the language had before the typed clause, and adding one must not take it
     * away: the sites decide, and each converts the `Object` answer itself.
     */
    @Test
    fun `an untyped clause still lets one handler answer different types`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            n: int = scratch 1",
                "            s: string = scratch 2",
                "            println(n)",
                "            println(s)",
                "            return n",
                "        } emit [int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            v: int = try Emitter.source() { a ->",
                "                if (a == 1) {",
                "                    nyan a",
                "                } else {",
                "                    nyan \"text\"",
                "                }",
                "            }",
                "            println(v)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("1\ntext\n1\n", run(loaded))
    }

    /** The clause may still be wrapped across lines with a result written after the `]`. */
    @Test
    fun `a wrapped clause may declare its result`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 2, 3",
                "            return v",
                "        } emit [",
                "            int,",
                "            int,",
                "        ] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            r: int = try Emitter.source() { a, b -> nyan a * b }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("6\n", run(loaded))
    }
}
