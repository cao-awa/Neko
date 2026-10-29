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
 * The `-> R` half is optional. Written, it says what a *valued* `nyan` must produce and what every
 * `scratch` site is worth; left out, it says the handler answers nothing at all — the same thing as
 * writing `-> void`. Both spellings are covered here, along with the refusals each of them implies.
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
     * A clause may leave the result out, and then its handlers answer nothing.
     *
     * This is the `-> void` case spelled by omission: the clause still names the values a `scratch`
     * hands over — that is what a `try` is matched on — but the handler has nothing to send back, so
     * the `scratch` site is a statement rather than a value. Before this was allowed, a function used
     * only for its failure path had to invent a result type it never produced.
     */
    @Test
    fun `an emit clause may leave its result out`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun ping() {",
                "            scratch 7",
                "            println(\"returned\")",
                "        } emit [int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try Emitter.ping() { n ->",
                "                println(\"handled \" + n)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        // The handler `nyan`s nothing, so the `scratch` site carries on and `ping` prints its own
        // line: the clause's values travel out, but no answer comes back in.
        assertEquals("handled 7\nreturned\n", run(loaded))
    }

    /** `-> void` says the same thing as leaving the result out, and is accepted for saying it. */
    @Test
    fun `an emit clause may name void as its result`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun ping() {",
                "            scratch 7",
                "            println(\"returned\")",
                "        } emit [int] -> void",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try Emitter.ping() { n ->",
                "                println(\"handled \" + n)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("handled 7\nreturned\n", run(loaded))
    }

    /** A clause naming no values may leave the result out too: `emit []`. */
    @Test
    fun `a clause naming no values may leave its result out`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun ping() {",
                "            scratch",
                "            println(\"returned\")",
                "        } emit []",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try Emitter.ping() { ->",
                "                println(\"handled\")",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("handled\nreturned\n", run(loaded))
    }

    /**
     * A valued `nyan` is refused when the clause promises nothing, and the message says what to write.
     *
     * The advice is "a bare `nyan`" rather than "a `void` value", because `void` is not a type a
     * program can produce a value of — telling the reader to write one would be advice they could not
     * act on.
     */
    @Test
    fun `a valued nyan is refused when the clause promises nothing`() {
        val messages = errorsOf(
            program(
                "class Emitter {",
                "    static {",
                "        fun ping() {",
                "            scratch 1",
                "        } emit [int]",
                "",
                "        fun run() {",
                "            try ping() { a -> nyan a }",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            messages.any { "promises no value" in it && "bare 'nyan'" in it },
            "expected the valued nyan to be refused, but got:\n${messages.joinToString("\n")}",
        )
    }

    /** A `scratch` from a void clause is worth nothing, so assigning it is refused. */
    @Test
    fun `a scratch from a void clause cannot be assigned`() {
        val messages = errorsOf(
            program(
                "class Emitter {",
                "    static {",
                "        fun ping() {",
                "            v: int = scratch 1",
                "            println(v)",
                "        } emit [int]",
                "    }",
                "}",
            ),
        )
        assertTrue(
            messages.any { "answers nothing" in it && "drop the assignment" in it },
            "expected the assignment to be refused, but got:\n${messages.joinToString("\n")}",
        )
    }

    /**
     * A wrapper may forward a void clause unchanged, exactly as it forwards a valued one.
     *
     * Both clauses name no result, so the handler passes straight through and nothing needs
     * converting — which is what makes a pass-through wrapper over a failure-only function possible
     * without inventing a result type.
     */
    @Test
    fun `a wrapper may forward a void clause`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source() {",
                "            scratch 3",
                "            println(\"returned\")",
                "        } emit [int]",
                "",
                "        fun wrapper() {",
                "            source()",
                "            println(\"wrapped\")",
                "        } emit [int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try Emitter.wrapper() { n ->",
                "                println(\"handled \" + n)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        // `nyan` answers the `scratch` site, which is inside `source` — so control comes back there
        // and `source` runs to its end, then `wrapper` does. Forwarding is what lets the handler
        // declared by `main`'s `try` be reached from two frames down.
        assertEquals("handled 3\nreturned\nwrapped\n", run(loaded))
    }

    /**
     * A wrapper may not forward a clause whose result differs from the one it declares.
     *
     * Forwarding passes the caller's handler straight into the callee, so a difference in `R` would
     * promise the caller one answer and hand its handler to sites expecting another. Matching the
     * value types is not enough: the result is part of the same contract. A `void` clause against a
     * valued one is such a difference too, which is why `null` is resolved to `void` rather than left
     * loose.
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

    /**
     * `void` names a type only after the clause's `->`, never anywhere else.
     *
     * The word is accepted in `emit [...] -> void` because that position asks what the handler
     * answers, and "nothing" is a real answer there. A local, parameter or field of type `void` would
     * have no value to hold, so the name stays unknown everywhere else — which is what keeps
     * `x: void = ...` an error rather than a puzzling one.
     */
    @Test
    fun `void is not a type name outside the clause's result`() {
        val messages = errorsOf(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            x: void = null",
                "            println(x)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            messages.isNotEmpty(),
            "a 'void' local should be refused, but nothing was reported",
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
