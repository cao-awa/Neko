package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules that `Sample.neko` states in its own comments.
 *
 * The sample is the specification for these behaviours: its comments say explicitly which lines must
 * compile and which must not, and the code around them pins down what each construct means. Each
 * test below quotes the comment it enforces, so a regression shows up as a failing test that names
 * the sample line it broke.
 *
 * Positions are asserted through [TestSupport.locationOf] rather than as literal line/column pairs.
 * The numbers then come from the same text the compiler saw, so editing a test's source cannot leave
 * a stale column behind — and a test that checks *where* the compiler pointed keeps working when the
 * fixture above it shifts.
 */
class SampleRulesTest {

    @Test
    fun `a bare declaration declares a mutable variable`() {
        // Sample line 12 (`str: string = "Hell world!"`) has no `val`, and line 18's
        // `str = "new value"` sits under "Successes to compile" — so a bare declaration is mutable.
        val source = TestSupport.program(
            "static {",
            "    fun variable() {",
            "        str: string = \"Hell world!\"",
            "        str = \"new value\"",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source)
        assertTrue(
            !result.hasErrors,
            "a declaration without 'val' is mutable, so reassigning it must compile:\n" +
                TestSupport.errorLocations(result).joinToString("\n"),
        )
    }

    @Test
    fun `redeclaring a variable in the same scope is an error at the name`() {
        // Sample line 15 is commented out under "Error to compile:" because line 12 already
        // declared `str` in the same scope.
        val source = TestSupport.program(
            "static {",
            "    fun variable() {",
            "        str: string = \"Hell world!\"",
            "        str: string = \"new value\"",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).single()

        assertEquals("variable 'str' is already declared in this scope", error.message)
        assertEquals(
            TestSupport.locationOf(source, "str", occurrence = 2),
            "${error.span?.line}:${error.span?.column}",
            "the error belongs on the second declaration's name",
        )

        val note = error.notes.single()
        assertTrue(
            note.message.contains("previous declaration"),
            "unexpected note: ${note.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "str"),
            "${note.span?.line}:${note.span?.column}",
            "the note should point at the first declaration's name",
        )
    }

    @Test
    fun `a 'val' local cannot be reassigned`() {
        val source = TestSupport.program(
            "static {",
            "    fun variable() {",
            "        val str: string = \"fixed\"",
            "        str = \"new value\"",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).single()
        assertTrue(
            error.message.contains("declared with 'val'"),
            "unexpected message: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "str", occurrence = 2),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the assignment target",
        )
    }

    @Test
    fun `a local is not in scope inside its own initializer`() {
        // Sample line 8: `lambda: int = lambda()(1,2)` resolves `lambda()` to the *function*
        // `lambda`, not to the variable being declared.
        val source = TestSupport.program(
            "static {",
            "    fun lambda(): [int, int] -> int {",
            "        return { x, y ->",
            "            yield x + y",
            "        }",
            "    }",
            "    fun main() {",
            "        lambda: int = lambda()(1, 2)",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source)
        assertTrue(
            !result.hasErrors,
            "an initializer must not see the variable it declares:\n" +
                TestSupport.errorLocations(result).joinToString("\n"),
        )
    }

    @Test
    fun `a chained comparison means all comparisons hold`() {
        // Sample line 30: `if (a > b > c)` with a=3, b=2, c=1 holds, and the sample prints.
        val program = TestSupport.load(
            TestSupport.program(
                "static {",
                "    fun check(a: int, b: int, c: int): bool {",
                "        return a > b > c",
                "    }",
                "    fun main() {",
                "        println(check(3, 2, 1))",
                "        println(check(1, 2, 3))",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("true\nfalse\n", output)
    }

    @Test
    fun `a chained comparison evaluates each operand exactly once`() {
        // Desugaring `a > middle() > c` into `(a > middle()) && (middle() > c)` would call it twice.
        val program = TestSupport.load(
            TestSupport.program(
                "static {",
                "    var calls: int = 0",
                "    fun middle(): int {",
                "        calls = calls + 1",
                "        return 2",
                "    }",
                "    fun check(a: int, c: int): bool {",
                "        return a > middle() > c",
                "    }",
                "    fun main() {",
                "        check(3, 1)",
                "        println(calls)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("1\n", output, "the middle operand must be evaluated once, not twice")
    }

    @Test
    fun `a chained comparison of non-ints is rejected`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static {",
                "    fun check(a: bool, b: bool): bool {",
                "        return a > b",
                "    }",
                "}",
            ),
        )
        assertTrue(result.hasErrors, "'>' on bool operands must be rejected")
        assertTrue(
            TestSupport.messages(result).any { it.contains("requires int operands") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `when selects the matching branch and ignores the rest`() {
        // Sample lines 34-39: `when (a)` with a=3 prints "Correct a" and never "Wrong a".
        val program = TestSupport.load(
            TestSupport.program(
                "static {",
                "    fun describe(a: int) {",
                "        when (a) {",
                "            3 -> println(\"Correct a\")",
                "            2 -> {",
                "                println(\"Wrong a\")",
                "            }",
                "        }",
                "    }",
                "    fun main() {",
                "        describe(3)",
                "        describe(2)",
                "        describe(9)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("Correct a\nWrong a\n", output, "only a matching branch may run")
    }

    @Test
    fun `when evaluates its subject once`() {
        val program = TestSupport.load(
            TestSupport.program(
                "static {",
                "    var calls: int = 0",
                "    fun subject(): int {",
                "        calls = calls + 1",
                "        return 2",
                "    }",
                "    fun main() {",
                "        when (subject()) {",
                "            1 -> println(\"one\")",
                "            2 -> println(\"two\")",
                "            3 -> println(\"three\")",
                "        }",
                "        println(calls)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("two\n1\n", output, "the subject must be evaluated once, not once per branch")
    }

    @Test
    fun `when with else runs the else branch when nothing matches`() {
        val program = TestSupport.load(
            TestSupport.program(
                "static {",
                "    fun describe(a: int) {",
                "        when (a) {",
                "            1 -> println(\"one\")",
                "            else -> println(\"other\")",
                "        }",
                "    }",
                "    fun main() {",
                "        describe(1)",
                "        describe(7)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("one\nother\n", output)
    }

    @Test
    fun `a branch testing the wrong type is rejected at the branch value`() {
        val source = TestSupport.program(
            "static {",
            "    fun describe(a: int) {",
            "        when (a) {",
            "            \"three\" -> println(\"no\")",
            "        }",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).single()
        assertTrue(
            error.message.contains("subject has type int"),
            "unexpected message: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "\"three\""),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the branch value",
        )
    }

    @Test
    fun `a duplicate else branch is rejected`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static {",
                "    fun describe(a: int) {",
                "        when (a) {",
                "            else -> println(\"first\")",
                "            else -> println(\"second\")",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.messages(result).any { it.contains("already has an 'else' branch") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `throw is rejected by naming what replaced it`() {
        // `throw` and `throws` were removed. A reader who writes one gets a diagnostic that says so
        // and points at where the replacement is documented, rather than a syntax error.
        val source = TestSupport.program(
            "static {",
            "    fun fails() {",
            "        throw CustomException()",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).single()
        assertTrue(
            error.message.contains("'throw' is not an Neko keyword"),
            "unexpected message: ${error.message}",
        )
        assertTrue(
            error.message.contains("'scratch <value>, ...'"),
            "the message should name the replacement: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "throw"),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the removed keyword",
        )
    }

    @Test
    fun `raise is rejected by explaining how its meaning changed`() {
        // `raise` was the keyword `scratch` replaced, but the two are not synonyms: `raise` left the
        // function and `scratch` returns from the handler call. A program ported from `raise` would
        // therefore still compile and quietly behave differently, so the diagnostic has to say what
        // changed rather than just name the new keyword.
        val source = TestSupport.program(
            "static {",
            "    fun sends() {",
            "        raise 1, 2",
            "    } emit [int, int]",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).single()
        assertTrue(
            error.message.contains("'raise' is not an Neko keyword"),
            "unexpected message: ${error.message}",
        )
        assertTrue(
            error.message.contains("does not leave the function"),
            "the message should say how the meaning changed: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "raise"),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the removed keyword",
        )
    }

    @Test
    fun `yield satisfies the requirement to return`() {
        // A function with an `emit` clause leaves by throwing rather than returning, so a `yield` on
        // every path is a legitimate finish — it needs no `return` to satisfy the check.
        val result = TestSupport.compile(
            TestSupport.file(
                "class Test {",
                "    static {",
                "        fun value() {",
                "            yield 1",
                "        } emit [int]",
                "    }",
                "}",
            ),
        )
        assertTrue(
            !result.hasErrors,
            "'yield' leaves the function, so no 'return' is needed:\n" +
                TestSupport.errorLocations(result).joinToString("\n"),
        )
    }

    @Test
    fun `a function returning a value must not fall off its end`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static {",
                "    fun value(): int {",
                "        println(\"no return\")",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.messages(result).any { it.contains("can finish without executing a 'return'") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a when whose every branch returns satisfies the requirement to return`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static {",
                "    fun value(a: int): int {",
                "        when (a) {",
                "            1 -> return 1",
                "            else -> return 2",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            !result.hasErrors,
            "a 'when' with an else whose branches all return must satisfy the return check:\n" +
                TestSupport.errorLocations(result).joinToString("\n"),
        )
    }
}
