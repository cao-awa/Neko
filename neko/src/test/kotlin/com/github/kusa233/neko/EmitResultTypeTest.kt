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
 * The `-> R` half is not optional: leaving it out is refused, because it is the half that says what a
 * `scratch` site is worth and what a valued `nyan` promises. One test here pins that refusal.
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
     * A clause that leaves the result out is refused, and the message says what to write.
     *
     * The result is half of the clause's contract — what every `scratch` site is worth and what a
     * valued `nyan` promises — so a clause without one is not the older "answer anything" agreement,
     * it is a clause the language cannot check. The parser still accepts the half-written clause so
     * this can be reported at the clause rather than as a parse failure.
     */
    @Test
    fun `an emit clause without a result is refused`() {
        val source = program(
            "class Emitter {",
            "    static {",
            "        fun source(): int {",
            "            v: int = scratch 1",
            "            return v",
            "        } emit [int]",
            "    }",
            "}",
        )
        val reported = TestSupport.errors(TestSupport.compile(source, name = "Emit.neko"))
        assertTrue(
            reported.any {
                "must name the result its handler answers" in it.message &&
                    "emit [...] -> R" in it.message
            },
            "expected the missing result to be named, but got:\n" +
                reported.joinToString("\n") { it.message },
        )
        // The diagnostic belongs on the half-written clause, which is where the fix goes.
        assertEquals(
            "emit [int]",
            reported.single().span?.text,
            "the error should point at the clause rather than at the function",
        )
    }

    @Test
    fun `a clause naming no values still has to name its result`() {
        val source = program(
            "class Emitter {",
            "    static {",
            "        fun ping(): int {",
            "            v: int = scratch",
            "            return v",
            "        } emit []",
            "    }",
            "}",
        )
        val reported = TestSupport.errors(TestSupport.compile(source, name = "Emit.neko"))
        assertTrue(
            reported.any { "must name the result its handler answers" in it.message },
            "an empty clause has a result too, but got:\n" +
                reported.joinToString("\n") { it.message },
        )
    }

    /**
     * A wrapper may not forward a clause whose result differs from the one it declares.
     *
     * Forwarding passes the caller's handler straight into the callee, so a difference in `R` would
     * promise the caller one answer and hand its handler to sites expecting another. Matching the
     * value types is not enough: the result is part of the same contract.
     */
    @Test
    fun `a wrapper may not forward a clause with a different result`() {
        val messages = errorsOf(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int] -> int",
                "",
                "        fun wrapper(): int {",
                "            return source()",
                "        } emit [int] -> string",
                "    }",
                "}",
            ),
        )
        assertTrue(
            messages.any { "'source' declares an 'emit' clause" in it && "call it as" in it },
            "expected the forwarding to be refused, but got:\n${messages.joinToString("\n")}",
        )
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
