package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `yield` and `nyan` are allowed to leave the value out.
 *
 * The clause fixes what a *valued* answer must be — `emit [...] -> R` — so a value written where the
 * clause names an `R` is checked, and a bare `nyan` is accepted wherever a value is wanted because
 * the missing answer has a meaning: `null`, which the site reads as `R`'s Java default. Two
 * spellings follow from that:
 *
 *  * a bare `nyan` is the handler's plain `return`; the site takes the default when it reads one,
 *    and nothing is read when it discards it;
 *  * when the `try` that declared the handler is a statement, it is worth nothing, so `yield` on its
 *    own is how a handler abandons the call without producing a value.
 *
 * Before this, both keywords demanded a value unconditionally, which forced a program to invent one —
 * `nyan 0`, `yield 0` — purely to satisfy the syntax while the value went nowhere. The tests below pin
 * down both directions: a bare form where nothing is read is accepted and does the right thing at run
 * time, and a bare `yield` where something *is* read is refused, because a missing value cannot be
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
     * `try risky() scratch 5` in statement position reads nothing, and the bare `nyan` in the handler
     * answers `null` — which nobody reads. The program has to *finish* the handler and carry on after
     * the `scratch`, which is what the printed line proves: the handler ran, and `after` ran next.
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
                "        } emit [int] -> int",
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
     * A bare `nyan` where a value is wanted answers that type's Java default.
     *
     * This used to be an error — "this 'nyan' leaves no value, but the 'scratch' sites reaching this
     * handler take int" — because the handler was a method whose return type the sites fixed, so
     * answering nothing there would have been a `void` return from an `int` method.
     *
     * A handler now answers an `Object`, and a bare `nyan` is a `null` answer. The site asking for an
     * `int` converts it to the Java default rather than throwing, which is what makes "no value" a
     * usable answer instead of a hole in the type system.
     */
    @Test
    fun `a bare nyan answers the type's default when a value is wanted`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        fun guard(): int {",
                "            v: int = scratch 5",
                "            return v",
                "        } emit [int] -> int",
                "",
                "        fun main() {",
                "            r: int = try guard() { x -> nyan }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("0", output, "a null answer becomes the int default at the site that wanted one")
    }

    /**
     * A valued `nyan` keeps working when nobody reads the answer.
     *
     * The handler still answers an `Object` at the boundary and the valued `nyan` is a `return` of
     * that value; nothing reads it here, which is fine. Allowing the value to be *omitted* must not
     * make writing it illegal.
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
                "        } emit [int] -> int",
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
                "        } emit [int] -> int",
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
                "        } emit [int] -> int",
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
                "        } emit [int] -> int",
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
     * A handler that says nothing at all is the same as a bare `nyan`: the site gets the default.
     *
     * The `{ ... }` block may simply end without `nyan` or `yield`. That is no longer a diagnostic
     * against the handler — the generated `scratching` answers `null` after the body — and the site
     * converts it exactly as it would a bare `nyan`.
     */
    @Test
    fun `a handler with no nyan answers the default too`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        fun guard(): int {",
                "            v: int = scratch 5",
                "            return v",
                "        } emit [int] -> int",
                "",
                "        fun main() {",
                "            r: int = try guard() { x ->",
                "                println(\"handler ran\")",
                "            }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("handler ran\n0", output, "the body ran and answered nothing, so the site got 0")
    }
}
