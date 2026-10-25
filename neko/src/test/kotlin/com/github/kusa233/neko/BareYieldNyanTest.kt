package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `yield` and `nyan` are allowed to leave the value out, and required to when nothing reads it.
 *
 * A handler answers the `scratch` sites that reach it, so *whether* a value travels out of it is
 * decided by those sites rather than by the handler's own wishes. Two spellings follow from that:
 *
 *  * when every site discards the answer, the handler is a `void` method, and `nyan` on its own is
 *    its plain `return`;
 *  * when the `try` that declared the handler is a statement, it is worth nothing, so `yield` on its
 *    own is how a handler abandons the call without producing one.
 *
 * Before this, both keywords demanded a value unconditionally, which forced a program to invent one —
 * `nyan 0`, `yield 0` — purely to satisfy the syntax while the value went nowhere. The tests below pin
 * down both directions: a bare form where nothing is read is accepted and does the right thing at run
 * time, and a bare form where something *is* read is refused, because a missing value cannot be
 * invented on the author's behalf.
 *
 * The programs are run, not merely compiled. The interesting failure here is silent: an unvalued
 * `yield` that answered instead of leaving would let the abandoned call carry on, which compiles and
 * verifies perfectly and prints the wrong thing.
 */
class BareYieldNyanTest {

    /** Compiles a class with the given members into package `com.github.kusa233.test`. */
    private fun program(vararg lines: String): String = TestSupport.file(*lines)

    /** Runs `main` in the generated `Test` class and returns what it printed, trimmed. */
    private fun run(source: String): String {
        val loaded = TestSupport.load(source)
        return loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.test.Test", "main")
        }.trim()
    }

    /** The errors reported for [source], as `line:column: message`. */
    private fun errors(source: String): List<String> =
        TestSupport.errorLocations(TestSupport.compile(source))

    // ------------------------------------------------------------------ bare `nyan`

    /**
     * A bare `nyan` is legal when the `scratch` sites discard the answer.
     *
     * `try risky() scratch 5` in statement position reads nothing, so the handler is emitted `void` and
     * `nyan` on its own is the `return`. The program has to *finish* the handler and carry on after the
     * `scratch`, which is what the printed line proves: the handler ran, and `after` ran next.
     */
    @Test
    fun `a bare nyan answers nothing when nothing reads it`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        var log: string = \"\"",
                "",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guard() {",
                "            try risky() scratch 5",
                "            log = log + \"|after\"",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            try guard() { x ->",
                "                log = log + \"|handler\"",
                "                nyan",
                "            }",
                "            println(log)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("|handler|after", output)
    }

    /**
     * A bare `nyan` is refused when the reaching sites want a value.
     *
     * `v: int = try risky() scratch 5` asks the handler for an `int`, so a `nyan` that answers nothing
     * would `return` from a method declared to answer one. The diagnostic names both sides of the
     * disagreement rather than silently coercing.
     */
    @Test
    fun `a bare nyan is refused when a value is wanted`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guard(): int {",
                "            v: int = try risky() scratch 5",
                "            return v",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            r: int = try guard() { x -> nyan }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "leaves no value" in it && "take int" in it },
            "expected the missing-value error, but got:\n${reported.joinToString("\n")}",
        )
    }

    /**
     * A valued `nyan` keeps working when nobody reads the answer.
     *
     * The handler is `void` because no site asks for anything, and a valued `nyan` there is a `return`
     * of that value, which the generator already emitted by fixing the handler's type from the `nyan`
     * itself. Allowing the value to be *omitted* must not make writing it illegal.
     */
    @Test
    fun `a valued nyan still works when nothing reads it`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        var done: string = \"\"",
                "",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guard() {",
                "            try risky() scratch 5",
                "            done = done + \"|ok\"",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            try guard() { x -> nyan 9 }",
                "            println(done)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("|ok", output)
    }

    // ------------------------------------------------------------------ bare `yield`

    /**
     * A bare `yield` abandons the call: the statements after the `scratch` must not run.
     *
     * This is the whole point of `yield` in a statement-position `try` — control leaves through the
     * `try` instead of continuing at the `scratch` site, so the call never returns to `guard`. The
     * program records each step it reaches, so the assertion pins down *which* statements ran: seeing
     * `|after` would mean the call carried on, which is the bug this test exists for.
     */
    @Test
    fun `a bare yield abandons the call`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        var log: string = \"\"",
                "",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guard() {",
                "            try risky() scratch 5",
                "            log = log + \"|after\"",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            try guard() { x ->",
                "                log = log + \"|handler\"",
                "                yield",
                "            }",
                "            log = log + \"|main\"",
                "            println(log)",
                "        }",
                "    }",
                "}",
            ),
        )
        // `|after` is absent: the `yield` left through the `try` rather than returning to `guard`.
        assertEquals("|handler|main", output)
    }

    /**
     * A bare `yield` is refused where the construct is worth a value.
     *
     * `r: int = try guard() { x -> yield }` would leave the `try` with nothing on the path where the
     * handler ran, while its other path produces an `int`. The value cannot be left out there.
     */
    @Test
    fun `a bare yield is refused when a value is wanted`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guard(): int {",
                "            v: int = try risky() scratch 5",
                "            return v",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            r: int = try guard() { x -> yield }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "'yield' must be followed by the value" in it },
            "expected the missing-value error, but got:\n${reported.joinToString("\n")}",
        )
    }

    /**
     * A valued `yield` in a statement-position `try` is still refused.
     *
     * The construct is worth nothing there, so a value would be produced for nobody. The diagnostic
     * now points at the bare form as the fix, which is the spelling this feature adds.
     */
    @Test
    fun `a valued yield in a statement try is refused`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guard() {",
                "            try risky() scratch 5",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            try guard() { x -> yield 7 }",
                "            println(\"x\")",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "worth nothing" in it && "bare 'yield'" in it },
            "expected the statement-try yield error, but got:\n${reported.joinToString("\n")}",
        )
    }

    // ------------------------------------------------------------------ what still must hold

    /**
     * A bare `yield` in a lambda is refused when the lambda is used where a value is expected.
     *
     * The `yield`/`nyan` relaxation is about `try` handlers, not about lambdas: a lambda answering an
     * `int` cannot `yield` nothing, for the same reason a function returning `int` cannot fall off its
     * end.
     */
    @Test
    fun `a bare yield is refused in a value-carrying lambda`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun main() {",
                "            f: [int] -> int = { x ->",
                "                yield",
                "            }",
                "            println(f(1))",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "'yield' must be followed by the value" in it },
            "expected the lambda yield error, but got:\n${reported.joinToString("\n")}",
        )
    }

    /**
     * Omitting the value where it *is* read is reported against the handler, not against the call.
     *
     * The position matters: the whole handler is what answers the sites, so the diagnostic points at
     * the `{ ... }` block rather than at the keyword — the fix may be in any branch of it, and naming
     * one statement would be arbitrary.
     */
    @Test
    fun `the missing value is reported against the handler`() {
        val source = program(
            "class Test {",
            "    static {",
            "        fun risky(): int {",
            "            return 1 / 0",
            "        }",
            "",
            "        fun guard(): int {",
            "            v: int = try risky() scratch 5",
            "            return v",
            "        } emit [int]",
            "",
            "        fun main() {",
            "            r: int = try guard() { x -> nyan }",
            "            println(r)",
            "        }",
            "    }",
            "}",
        )
        // The diagnostic points at the handler's `{ ... }` body, which is the line holding `nyan`
        // here — the whole block is what answers the sites, so naming one statement would be arbitrary.
        val handlerLine = TestSupport.locationOf(source, "x -> nyan }").substringBefore(':')
        val reported = TestSupport.errorLocations(TestSupport.compile(source))
        assertTrue(
            reported.any { it.startsWith("Test.neko:$handlerLine:") },
            "expected the error on the handler line $handlerLine, but got:\n${reported.joinToString("\n")}",
        )
    }
}
