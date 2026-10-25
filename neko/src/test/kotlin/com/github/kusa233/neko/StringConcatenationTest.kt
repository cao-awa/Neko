package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `+` where one side is a `string` — concatenation of **any** type.
 *
 * This used to accept `string + string` only. Everything else was refused with "operator '+' requires
 * int operands but this operand has type string", which named the operand that was *right* as the
 * mistake, and for `1L` went further and advised dropping the `L` — turning a `long` into an `int`.
 * Since `"n = " + count` is the reason to have string building at all, the rule is now: if either side
 * is a `string`, the `+` concatenates, and the other side may be of any type the JVM can print.
 *
 * The tests below **run** the generated code rather than only compiling it, because the interesting
 * failures here are silently wrong output, not refusals. A `char` appended through `append(int)`
 * compiles and verifies perfectly and prints `120` instead of `x`; a `long` appended through
 * `append(int)` prints a truncated number. Only running the program catches either.
 */
class StringConcatenationTest {

    /** Compiles [bodyLines] as a `main`, runs it, and returns what it printed, trimmed. */
    private fun run(vararg bodyLines: String): String {
        val program = TestSupport.load(
            TestSupport.program(
                "static {",
                "    fun main() {",
                *bodyLines.map { "        $it" }.toTypedArray(),
                "    }",
                "}",
            ),
        )
        return program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }.trim()
    }

    private fun messages(vararg bodyLines: String): List<String> = TestSupport.messages(
        TestSupport.compile(
            TestSupport.program(
                "static {",
                "    fun main() {",
                *bodyLines.map { "        $it" }.toTypedArray(),
                "    }",
                "}",
            ),
        ),
    )

    // ------------------------------------------------------------------ the point

    @Test
    fun `a string on the left concatenates a number`() {
        assertEquals("n=42", run("println(\"n=\" + 42)"))
    }

    @Test
    fun `a string on the right concatenates a number`() {
        // Order decides which `append` runs, not whether concatenation happens at all.
        assertEquals("42=n", run("println(42 + \"=n\")"))
    }

    @Test
    fun `every primitive prints as itself`() {
        // One line per type, because each picks a different `append` overload: a wrong one among
        // these shows up as a number where a character or a `true` belongs.
        val output = run(
            "b: byte = 7",
            "s: short = 8",
            "c: char = 'x'",
            "i: int = 9",
            "l: long = 10L",
            "f: float = 1.5f",
            "d: double = 2.5",
            "z: bool = true",
            "println(\"\" + b)",
            "println(\"\" + s)",
            "println(\"\" + c)",
            "println(\"\" + i)",
            "println(\"\" + l)",
            "println(\"\" + f)",
            "println(\"\" + d)",
            "println(\"\" + z)",
        )

        assertEquals(listOf("7", "8", "x", "9", "10", "1.5", "2.5", "true"), output.lines())
    }

    @Test
    fun `a long keeps its value rather than being narrowed`() {
        // The regression for the old advice: `1L + "text"` was rejected, and the message told the
        // reader to write `1` — which would have changed the type of the value being printed.
        // `3000000000L` does not fit an `int`, so a narrowing would be visible, not silent.
        assertEquals("3000000000", run("println(\"\" + 3000000000L)"))
    }

    @Test
    fun `a class instance prints through its own toString`() {
        // `append(Object)` is `String.valueOf`, so the object's `toString` decides. This is what makes
        // `"at " + point` useful instead of printing a hash code.
        val program = TestSupport.load(
            TestSupport.program(
                "fun toString(): string {",
                "    return \"<point>\"",
                "}",
                "",
                "static {",
                "    fun main() {",
                "        p: Test = Test()",
                "        println(\"p=\" + p)",
                "    }",
                "}",
            ),
        )

        assertEquals("p=<point>", program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }.trim())
    }

    @Test
    fun `an absent value prints as the word null`() {
        // `append(Object)` prints `null` for a null reference; it does not throw, and the message a
        // reader gets is the one they expect for a missing value.
        assertEquals("o=null", run("o: Object? = null", "println(\"o=\" + o)"))
    }

    @Test
    fun `an Object holding a string prints the string`() {
        assertEquals("o=boxed", run("o: Object = \"boxed\"", "println(\"o=\" + o)"))
    }

    // ------------------------------------------------------------------ chains

    @Test
    fun `a chain keeps concatenating left to right`() {
        // `"x" + 1` is already a `string`, so the `+ true` beside it concatenates too.
        assertEquals("x1true", run("println(\"x\" + 1 + true)"))
    }

    @Test
    fun `a number on both sides of a string is still concatenation`() {
        // `1 + "a"` concatenates because the right is a string; the result is a `string`, so the
        // following `+ 2` concatenates as well rather than being read as arithmetic.
        assertEquals("1a2", run("println(1 + \"a\" + 2)"))
    }

    @Test
    fun `an arithmetic subexpression is evaluated before it is appended`() {
        assertEquals("sum=3", run("println(\"sum=\" + (1 + 2))"))
    }

    // ------------------------------------------------------- what stays refused

    @Test
    fun `arithmetic between two non-strings is unaffected`() {
        // The rule only widens `+` beside a `string`; `1 + 2` is still arithmetic and answers an `int`.
        assertEquals("3", run("println(1 + 2)"))
    }

    @Test
    fun `a value derived from a string is not silently converted`() {
        // Assigning an `int` where the declaration says `string` is still a type error: neither
        // operand is a string, so the `+` is arithmetic. The message names the real mistake — the
        // initializer's type — rather than blaming an operand.
        val messages = messages("s: string = 1 + 2")

        assertTrue(
            messages.any { "declared as string but its initializer has type int" in it },
            "expected the initializer's type to be reported, but got: $messages",
        )
    }

    @Test
    fun `a call producing no value cannot be concatenated`() {
        // A `void` function has nothing to append; the alternative is an `append` with nothing under
        // it on the stack, which the JVM would reject when the class is loaded.
        val messages = TestSupport.messages(
            TestSupport.compile(
                TestSupport.program(
                    "fun nothing() {",
                    "}",
                    "",
                    "static {",
                    "    fun main() {",
                    "        println(\"a\" + nothing())",
                    "    }",
                    "}",
                ),
            ),
        )

        assertTrue(
            messages.any { "produces no value" in it },
            "expected the void operand to be refused, but got: $messages",
        )
    }

    @Test
    fun `two nulls say nothing about what type they are absent from`() {
        val messages = messages("println(null + null)")

        assertTrue(
            messages.any { "nothing here says what type it should be absent from" in it },
            "expected the null literal to be reported, but got: $messages",
        )
    }

    @Test
    fun `subtracting strings is still arithmetic`() {
        // Only `+` concatenates. `-` keeps the numeric rule and its message.
        val messages = messages("println(\"a\" - \"b\")")

        assertTrue(
            messages.any { "int operands" in it },
            "expected the numeric rule to apply, but got: $messages",
        )
    }
}
