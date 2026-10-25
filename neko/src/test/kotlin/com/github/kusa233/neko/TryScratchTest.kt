package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `try call(...) scratch v1, v2` — catching a **Java exception** out of a call.
 *
 * The two `try` spellings answer different questions, and keeping them apart is what this file is
 * about:
 *
 *  * `try call(...) { a, b -> ... }` **declares** a handler, and catches the `yield` that handler
 *    throws on its way out of the call;
 *  * `try call(...) scratch v1, v2` **enters** a handler declared elsewhere, and catches the
 *    `java.lang.Exception` the call threw.
 *
 * So the brace form says *what should happen when this call scratches*, while this form says *what
 * should happen when this call fails*. Neither is a general-purpose `catch` block: the program still
 * cannot read the exception object, and this form is deliberately not a way to write `try { } catch`.
 *
 * The tests **run** the generated code rather than only compiling it, because every interesting
 * property here is about control flow at run time: which of the two paths was taken, and what value
 * came out of it. A test that only asserts "it compiled" would pass for a `try` that answered the
 * call's value on both paths.
 */
class TryScratchTest {

    /** Compiles a class with the given members into package `com.github.kusa233.test`. */
    private fun program(vararg lines: String): String = TestSupport.file(*lines)

    /** Runs `main` in the generated `Test` class and returns what it printed, trimmed. */
    private fun run(source: String): String {
        val loaded = TestSupport.load(source)
        return loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.test.Test", "main")
        }.trim()
    }

    /** The messages reported for [source], in production order. */
    private fun messages(source: String): List<String> = TestSupport.messages(TestSupport.compile(source))

    /** The error rendered as `line:column: message` for each error, in production order. */
    private fun errors(source: String): List<String> = TestSupport.errorLocations(TestSupport.compile(source))

    // ------------------------------------------------------------------ the value form

    /**
     * A call that throws is answered by the handler, a call that returns is not.
     *
     * This is the whole feature in one program: `risky(0)` divides by zero, so the handler answers;
     * `risky(2)` returns 50, so the assignment keeps the call's own value. Both are the same written
     * source, differing only in the argument, which is what makes it a real test of the two paths
     * rather than of two programs.
     */
    @Test
    fun `a thrown exception is answered by the handler and a return is not`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        fun risky(d: int): int {",
                "            return 100 / d",
                "        }",
                "",
                "        fun thrown(): int {",
                "            v: int = try risky(0) scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun returned(): int {",
                "            v: int = try risky(2) scratch 1, 2",
                "            return v",
                "        } emit [int, int]",
                "",
                "        fun main() {",
                "            a: int = try thrown() { x -> nyan x + 2 }",
                "            println(a)",
                "            b: int = try returned() { x, y -> nyan x + y }",
                "            println(b)",
                "        }",
                "    }",
                "}",
            ),
        )
        // 3 = the handler's `1 + 2`; 50 = `100 / 2` from the call itself.
        assertEquals("3\n50", output)
    }

    /**
     * The `scratch` values are what the handler receives — the exception object is not smuggled in.
     *
     * `try risky(0) scratch 7, 8` must reach a handler taking two `int`s and answer 15. If the
     * construct passed the exception as an extra implicit argument, the handler's `a + b` would not
     * even be the two values written here, so the arithmetic is the assertion.
     */
    @Test
    fun `the scratch values are exactly what the handler receives`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guarded(): int {",
                "            v: int = try risky() scratch 7, 8",
                "            return v",
                "        } emit [int, int]",
                "",
                "        fun main() {",
                "            r: int = try guarded() { a, b -> nyan a + b }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("15", output)
    }

    // ------------------------------------------------------------------ the statement form

    /**
     * The statement form discards both answers, so a `void` call is legal there.
     *
     * The value form refuses a `void` call — there would be nothing to answer on the path where it
     * returned — but a statement is not asking for anything, so the same callee is fine. That
     * asymmetry is the reason the two forms are separate nodes rather than one with a flag.
     */
    @Test
    fun `the statement form accepts a void call`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        var seen: int = 0",
                "",
                "        fun risky() {",
                "            seen = 1 / 0",
                "        }",
                "",
                "        fun guarded() {",
                "            try risky() scratch 5",
                "            println(\"survived\")",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            try guarded() { x -> nyan x * 2 }",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("survived", output)
    }

    // ------------------------------------------------------------------ what must not be caught

    /**
     * A `yield` on its way out is **not** this construct's failure and must pass through.
     *
     * `inner` scratches, which throws `EmitYieldException`; `outer` wraps that call in
     * `try inner() scratch 99`. If the `catch` here swallowed the exception, `outer` would answer
     * **99**. Because it is rethrown, the `yield` reaches the `try` that declared the handler, which
     * answers `21 + 1 = 22`.
     *
     * This is the single most important property of the feature: without the rethrow, wrapping a call
     * in this form would silently divert every effect that travels through it.
     */
    @Test
    fun `a yield passes through instead of being caught`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        fun inner(): int {",
                "            v: int = scratch 21",
                "            return v",
                "        } emit [int]",
                "",
                "        fun outer(): int {",
                "            w: int = try inner() scratch 99",
                "            return w",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            r: int = try outer() { a -> nyan a + 1 }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        // 22 proves the yield reached `main`'s handler; 99 would mean `outer` ate it.
        assertEquals("22", output)
    }

    /**
     * An `Error` is not an `Exception`, so it must not be caught either.
     *
     * The `catch` names `java.lang.Exception` rather than `Throwable` on purpose. A handler is
     * ordinary program logic, and `OutOfMemoryError`/`StackOverflowError` are not conditions ordinary
     * program logic can meaningfully recover from — swallowing one would turn a fatal failure into a
     * wrong answer.
     */
    @Test
    fun `an error propagates instead of being caught`() {
        val loaded = TestSupport.load(
            program(
                "class Test {",
                "    static {",
                "        fun deep(d: int): int {",
                "            return deep(d + 1)",
                "        }",
                "",
                "        fun guarded(): int {",
                "            v: int = try deep(0) scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            r: int = try guarded() { a -> nyan 999 }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        val failure = runCatching {
            loaded.capturingOutput {
                loaded.invoke("com.github.kusa233.test.Test", "main")
            }
        }.exceptionOrNull()
        assertTrue(
            failure is StackOverflowError,
            "expected the StackOverflowError to escape, but got $failure",
        )
    }

    // ------------------------------------------------------------------ rejections

    /**
     * The `scratch` names which handler to enter, so the enclosing function has to declare the
     * `emit` clause that says which one it is.
     *
     * Without it there is no destination to name, and the construct would have to swallow the
     * exception rather than send it anywhere — which is exactly what Neko does not offer.
     */
    @Test
    fun `a scratch outside an emit clause is refused even inside a try`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun plain(): int {",
                "            v: int = try risky() scratch 1",
                "            return v",
                "        }",
                "",
                "        fun main() {",
                "            println(\"x\")",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "'scratch' is only allowed inside a function that declares an 'emit' clause" in it },
            "expected the emit requirement to be reported, but got:\n${reported.joinToString("\n")}",
        )
    }

    /**
     * A `void` call cannot be the value of this construct.
     *
     * One of the two paths would answer the handler's value and the other nothing, so the construct
     * would have no type. The diagnostic points at the assignment the author should drop.
     */
    @Test
    fun `a void call cannot carry a value`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun silent() {",
                "            println(\"hi\")",
                "        }",
                "",
                "        fun guarded(): int {",
                "            v: int = try silent() scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            println(\"x\")",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "is used as a value" in it && "returns void" in it },
            "expected the void-value error, but got:\n${reported.joinToString("\n")}",
        )
    }

    /**
     * The call's return type and the handler's answer have to agree.
     *
     * They are two answers to the same question, so a `try` that is worth `int` when the handler
     * answers cannot be worth the call's `string` when nothing was thrown. This is the same rule a
     * `yield`-carrying `try` already follows, applied to the other path.
     */
    @Test
    fun `the handler's answer and the call's return type must agree`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): string {",
                "            return \"a\"",
                "        }",
                "",
                "        fun guarded(): int {",
                "            v: int = try risky() scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            println(\"x\")",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "this 'try' is worth int" in it && "returns string" in it },
            "expected the type-disagreement error, but got:\n${reported.joinToString("\n")}",
        )
    }

    /**
     * The construct needs a handler to enter, like any other `scratch` site.
     *
     * `try f() scratch 1` names a destination; if no `try` in the program declares a handler with
     * that `emit` signature, the site has nowhere to go and is reported as such.
     */
    @Test
    fun `a try scratch with no matching handler is refused`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guarded(): int {",
                "            v: int = try risky() scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            println(\"x\")",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "no 'try' declares a handler for this emit signature" in it },
            "expected the missing-handler error, but got:\n${reported.joinToString("\n")}",
        )
    }

    /**
     * The values written after `scratch` must match the enclosing function's `emit` clause.
     *
     * A two-value clause needs two values; one is a real mismatch, not a shortcut, because the
     * generated method takes exactly what the clause declares.
     */
    @Test
    fun `the scratch values must match the emit clause`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guarded(): int {",
                "            v: int = try risky() scratch 1",
                "            return v",
                "        } emit [int, int]",
                "",
                "        fun main() {",
                "            r: int = try guarded() { a, b -> nyan a + b }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "must provide 2 value(s)" in it },
            "expected the arity error, but got:\n${reported.joinToString("\n")}",
        )
    }

    // ------------------------------------------------------------------ parsing

    /**
     * `try` without a tail is still refused, and the message names both spellings.
     *
     * The new form makes `try f()` a legal prefix of two constructs, so the diagnostic for a call
     * with neither a block nor a `scratch` has to mention both rather than only the brace form.
     */
    @Test
    fun `try with neither a block nor a scratch is refused`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun risky(): int {",
                "            return 1",
                "        }",
                "",
                "        fun main() {",
                "            v: int = try risky()",
                "            println(v)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "expected '{'" in it || "must be followed by" in it },
            "expected a parse error about the missing tail, but got:\n${reported.joinToString("\n")}",
        )
    }

    /**
     * A `try` block is still refused with the wording about the call form.
     *
     * The feature adds a second tail, not a `try { }` block: a bare `try {` was removed deliberately
     * so that every `try` names the call it guards.
     */
    @Test
    fun `a bare try block is still refused`() {
        val reported = errors(
            program(
                "class Test {",
                "    static {",
                "        fun main() {",
                "            try {",
                "                println(\"x\")",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            reported.any { "a 'try' block no longer exists" in it },
            "expected the removed-block error, but got:\n${reported.joinToString("\n")}",
        )
    }

    // ------------------------------------------------------------------ nesting and shape

    /**
     * The construct nests inside a helper that another `try` guards.
     *
     * The nesting is deliberately written in a *function* rather than inside a `{ ... }` handler
     * body: a handler is compiled into a method of its own which does not inherit the enclosing
     * function's `emit` clause, so a `scratch` — this form or any other — is refused there. That is a
     * property of `emit` clauses rather than of this feature, and it is worth pinning down so the
     * limitation is recorded rather than rediscovered.
     */
    @Test
    fun `a try scratch nests inside a guarded helper`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        fun boom(): int {",
                "            return 1 / 0",
                "        }",
                "",
                "        fun guarded(): int {",
                "            v: int = try boom() scratch 4",
                "            return v",
                "        } emit [int]",
                "",
                "        fun helper(): int {",
                "            inner: int = try boom() scratch 5, 6",
                "            return inner",
                "        } emit [int, int]",
                "",
                "        fun main() {",
                "            r: int = try guarded() { a -> nyan a * 5 }",
                "            println(r)",
                "            s: int = try helper() { p, q -> nyan p - q }",
                "            println(s)",
                "        }",
                "    }",
                "}",
            ),
        )
        // 20 = the handler's `4 * 5`; `boom` threw, so the `scratch` entered `main`'s handler.
        // -1 = `5 - 6`, the second guarded helper answering through a handler of its own signature.
        assertEquals("20\n-1", output)
    }

    /**
     * The expression form is worth the callee's return type when nothing is thrown.
     *
     * The statement form has no type at all; this pins down that the value form does, by using it as
     * an argument rather than as an initialiser — a position that only accepts a real value.
     */
    @Test
    fun `the value form can be used as an argument`() {
        val output = run(
            program(
                "class Test {",
                "    static {",
                "        fun risky(d: int): int {",
                "            return 10 / d",
                "        }",
                "",
                "        fun guarded(): int {",
                "            return try risky(0) scratch 3",
                "        } emit [int]",
                "",
                "        fun show(n: int) {",
                "            println(\"n=\" + n)",
                "        }",
                "",
                "        fun main() {",
                "            r: int = try guarded() { x -> nyan x * 5 }",
                "            show(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("n=15", output)
    }
}
