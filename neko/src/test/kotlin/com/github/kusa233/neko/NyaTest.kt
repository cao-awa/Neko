package com.github.kusa233.neko

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for `nya condition` — a check that says what a false condition means.
 *
 * The point of the construct is what a *run* does, so these tests call the compiled program and read
 * what comes back. It has three forms, and each is tested for its own behaviour:
 *
 *  * `nya a == b` — a failed check ends the call with an `AssertionError` carrying the condition's own
 *    source text. Asserting on the message matters as much as on the throw: a check that fails without
 *    saying what was being checked is a worse diagnostic than no check at all.
 *  * `nya a == b { ... }` — the block handles the failure and **control carries on after it**, which is
 *    what replaced `try`/`catch` as the way a program recovers.
 *  * `nya a == b { ... } else { ... }` — worth a value, one block per reading of the condition.
 */
class NyaTest {

    private fun file(vararg lines: String) = TestSupport.file(*lines)

    /** The `AssertionError` [body] throws, failing the test when it does not throw one. */
    private fun assertionErrorFrom(body: () -> Unit): Throwable = try {
        body()
        fail("expected the program to fail its assertion")
    } catch (error: Throwable) {
        error
    }

    /**
     * What [body] printed, whether or not it ended in a failed check.
     *
     * [TestSupport.LoadedProgram.capturingOutput] answers only when the body returns, and the two
     * cases that need this are exactly the ones where it does not — a failure part-way through. So
     * the output is collected here with the throwable deliberately swallowed: the throw is asserted
     * separately, and what these tests are about is the output printed *before* it.
     */
    private fun printedBeforeFailure(body: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            body()
        } catch (_: Throwable) {
            // Expected: the check failed. The output collected so far is still the answer.
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `a check that holds lets the program carry on`() {
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            nya 1 + 1 == 2",
                "            println(\"carried on\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("carried on\n", output)
    }

    @Test
    fun `a check that fails throws an AssertionError`() {
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            nya 1 == 2",
                "            println(\"unreachable\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val error = assertionErrorFrom {
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        }
        assertTrue(
            error is AssertionError,
            "a failed check should throw AssertionError but threw ${error::class.qualifiedName}",
        )
    }

    @Test
    fun `the failure names the condition that was checked`() {
        // The message is the condition's own source text rather than a string the reader wrote, so it
        // cannot drift from what the check actually tests.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            count: int = 3",
                "            nya count == 4",
                "        }",
                "    }",
                "}",
            ),
        )
        val error = assertionErrorFrom {
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        }
        assertEquals("count == 4", error.message)
    }

    @Test
    fun `nothing after a failed check runs`() {
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun noisy() {",
                "            println(\"before\")",
                "            nya false",
                "            println(\"after\")",
                "        }",
                "",
                "        fun main() {",
                "            noisy()",
                "        }",
                "    }",
                "}",
            ),
        )
        var output = ""
        assertionErrorFrom { output = printedBeforeFailure { program.invoke("com.github.kusa233.test.Main", "main") } }
        assertEquals("before\n", output, "the statement after a failed check must not run")
    }

    @Test
    fun `a check may name a function rather than a comparison`() {
        // The operand is a whole expression, so anything that answers a `bool` is a check.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun ready(): bool {",
                "            return true",
                "        }",
                "",
                "        fun main() {",
                "            nya ready()",
                "            println(\"ready\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("ready\n", output)
    }

    @Test
    fun `a check is not switched off by a flag`() {
        // Unlike Java's `assert`, a `nya` is always compiled in: there is no `-ea` to forget, which is
        // the whole reason the statement exists rather than a call to a library function. `nya false`
        // is used here on purpose — a condition that can only fail compiles, and still throws when it
        // runs, which is what "always on" means. Proving the condition can never hold is not the
        // analyzer's job, so a refusal here would be a different feature than the one promised.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            nya false",
                "        }",
                "    }",
                "}",
            ),
        )
        val error = assertionErrorFrom {
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        }
        assertTrue(error is AssertionError, "an always-on check must still throw")
    }

    @Test
    fun `a non-bool condition is refused`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "    static {",
                "        fun main() {",
                "            nya 1 + 1",
                "        }",
                "    }",
            ),
        )
        assertEquals(
            listOf("the condition of 'nya' must be bool but has type int"),
            TestSupport.messages(result),
        )
    }

    @Test
    fun `a check with no condition is refused`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "    static {",
                "        fun main() {",
                "            nya",
                "        }",
                "    }",
            ),
        )
        assertTrue(
            TestSupport.messages(result).single().contains("'nya' requires a condition to check"),
            "unexpected message: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a trailing comma is not a failure message`() {
        // The statement has one operand. `nya a == b, "why"` is reported at the comma rather than
        // silently ignored, because a reader who wrote a message would otherwise think it was used.
        val result = TestSupport.compile(
            TestSupport.program(
                "    static {",
                "        fun main() {",
                "            nya 1 == 1, \"why\"",
                "        }",
                "    }",
            ),
        )
        assertTrue(
            TestSupport.errors(result).isNotEmpty(),
            "a second operand should be reported, but the program was accepted",
        )
    }

    // ------------------------------------------------------------------ narrowing

    @Test
    fun `a check narrows a nullable value for the code after it`() {
        // A `nya` that holds is the ordinary way to say "from here on this is there", exactly as a
        // guard is — so the read after it needs no `?.`.
        val program = TestSupport.load(
            file(
                "class Holder {",
                "    val value: int = 4",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            h: Holder? = Holder()",
                "            nya h != null",
                "            println(h.value)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("4\n", output)
    }

    @Test
    fun `the narrowing a check gives is the one its condition proves`() {
        // `nya a && b` proves both sides, so a read of either is allowed afterwards.
        val program = TestSupport.load(
            file(
                "class Holder {",
                "    val value: int? = 4",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            h: Holder? = Holder()",
                "            if (h == null) {",
                "                return",
                "            }",
                "            nya h.value != null",
                "            println(h.value + 1)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("5\n", output)
    }

    @Test
    fun `a failed check inside a function stops that call`() {
        // The throw leaves the function that holds the `nya` and reaches the caller — nothing inside
        // the language catches it, so the call is over.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun check(n: int) {",
                "            nya n > 0",
                "            println(\"positive\")",
                "        }",
                "",
                "        fun main() {",
                "            check(1)",
                "            check(0)",
                "            println(\"unreachable\")",
                "        }",
                "    }",
                "}",
            ),
        )
        var output = ""
        assertionErrorFrom { output = printedBeforeFailure { program.invoke("com.github.kusa233.test.Main", "main") } }
        assertEquals("positive\n", output)
    }

    // ------------------------------------------------------------ the failure block

    @Test
    fun `a failure block handles the failure and the program carries on`() {
        // This is what replaced `try`/`catch`: the false case is answered where it happened, and the
        // statement after the check runs as if nothing had gone wrong.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            nya 1 == 2 {",
                "                println(\"assert error\")",
                "            }",
                "            println(\"carried on\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("assert error\ncarried on\n", output)
    }

    @Test
    fun `a failure block is skipped when the check holds`() {
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            nya 1 + 1 == 2 {",
                "                println(\"assert error\")",
                "            }",
                "            println(\"carried on\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("carried on\n", output)
    }

    @Test
    fun `a failure block recovers rather than throwing`() {
        // The whole point of removing `try`/`catch`: a check whose failure is handled is not fatal, so
        // nothing escapes the function.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun check(n: int) {",
                "            nya n > 0 {",
                "                println(\"recovered\")",
                "            }",
                "            println(\"done\")",
                "        }",
                "",
                "        fun main() {",
                "            check(0)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("recovered\ndone\n", output)
    }

    @Test
    fun `a check with a block does not narrow after it`() {
        // Control reaches the next statement either way, so nothing is proved by then: on the reading
        // where `h` was absent the block has already run, and that says nothing about `h` now.
        val result = TestSupport.compile(
            file(
                "class Holder {",
                "    val value: int = 4",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            h: Holder? = Holder()",
                "            nya h != null {",
                "                println(\"missing\")",
                "            }",
                "            println(h.value)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("nullable") },
            "reading h after a recovered check should need '?.': ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a failure block narrows on what a false condition proves`() {
        // The block runs on the reading where the condition did *not* hold, so it is the place the
        // negation is known — which is the mirror image of what the code after a bare check knows.
        // `nya h == null` that is false means `h` is there, so the block may read it.
        val program = TestSupport.load(
            file(
                "class Holder {",
                "    val value: int = 4",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            h: Holder? = Holder()",
                "            nya h == null {",
                "                println(h.value)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("4\n", output)
    }

    // ------------------------------------------------------------ the value form

    @Test
    fun `a check with both blocks is worth the value its blocks yield`() {
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun pick(a: int, b: int): int {",
                "            v: int = nya a == b {",
                "                yield 1",
                "            } else {",
                "                yield 2",
                "            }",
                "            return v",
                "        }",
                "",
                "        fun main() {",
                "            println(pick(1, 1))",
                "            println(pick(1, 2))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        // `else` is the reading where the condition held, so `pick(1, 1)` answers 2 and `pick(1, 2)`
        // falls into the failure block and answers 1.
        assertEquals("2\n1\n", output)
    }

    @Test
    fun `the value form needs both blocks`() {
        // With only a failure block there is nothing to say what the expression is worth when the
        // condition holds, so the construct means nothing in value position.
        val result = TestSupport.compile(
            TestSupport.program(
                "    static {",
                "        fun main() {",
                "            v: int = nya 1 == 2 {",
                "                yield 1",
                "            }",
                "        }",
                "    }",
            ),
        )
        assertTrue(
            TestSupport.errors(result).isNotEmpty(),
            "a lone failure block in value position should be reported",
        )
    }

    @Test
    fun `a value-form block that never yields is refused`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "    static {",
                "        fun main() {",
                "            v: int = nya 1 == 2 {",
                "                yield 1",
                "            } else {",
                "                println(\"nothing\")",
                "            }",
                "        }",
                "    }",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("never 'yield'") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `the two blocks of a value form must yield the same type`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "    static {",
                "        fun main() {",
                "            v: int = nya 1 == 2 {",
                "                yield 1",
                "            } else {",
                "                yield \"no\"",
                "            }",
                "        }",
                "    }",
            ),
        )
        assertTrue(
            TestSupport.errors(result).isNotEmpty(),
            "blocks yielding different types should be reported",
        )
    }

    @Test
    fun `a value form narrows inside each of its blocks`() {
        // Each block is analysed under its own reading of the condition, so the `else` block is a
        // place where `h` is known to be there and the failure block is a place where it is not.
        val program = TestSupport.load(
            file(
                "class Holder {",
                "    val value: int = 4",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun describe(h: Holder?): int {",
                "            v: int = nya h != null {",
                "                yield 0",
                "            } else {",
                "                yield h.value",
                "            }",
                "            return v",
                "        }",
                "",
                "        fun main() {",
                "            println(describe(Holder()))",
                "            println(describe(null))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("4\n0\n", output)
    }

    @Test
    fun `a value form is a check first, so the failure block may not run when it holds`() {
        // The value form is still a check: a condition that holds takes the `else` branch and never
        // touches the failure block, which is what makes the two blocks mean different things.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            n: int = 5",
                "            v: int = nya n > 0 {",
                "                println(\"failed\")",
                "                yield 0",
                "            } else {",
                "                yield n",
                "            }",
                "            println(v)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("5\n", output)
    }

    @Test
    fun `a bare check cannot be used as a value`() {
        // A bare `nya` can only throw, so it answers nothing; using it where a value is expected has
        // no meaning to give it.
        val result = TestSupport.compile(
            TestSupport.program(
                "    static {",
                "        fun main() {",
                "            v: int = nya 1 == 2",
                "        }",
                "    }",
            ),
        )
        assertTrue(
            TestSupport.errors(result).isNotEmpty(),
            "a bare check cannot produce a value, but the program was accepted",
        )
    }

    @Test
    fun `a value form may be worth a reference and used straight away`() {
        // A value is a value wherever it is written, so the construct may stand as an argument
        // rather than only as an initializer. A `string` is also the case a value's *representation*
        // differs on: an `int` result travels boxed, and a `string` one is already a reference.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun label(ok: bool): string {",
                "            return nya ok {",
                "                yield \"no\"",
                "            } else {",
                "                yield \"yes\"",
                "            }",
                "        }",
                "",
                "        fun main() {",
                "            println(label(true))",
                "            println(label(false))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("yes\nno\n", output)
    }

    @Test
    fun `a value form whose blocks yield a nullable primitive still runs`() {
        // An `int?` is stored boxed, so this is the case where the value has to cross between the
        // boxed representation the two blocks agree on and the primitive the expression is worth.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun maybe(n: int): int? {",
                "            return nya n > 0 {",
                "                yield null",
                "            } else {",
                "                yield n",
                "            }",
                "        }",
                "",
                "        fun main() {",
                "            println(maybe(7))",
                "            println(maybe(0))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("7\nnull\n", output)
    }

    @Test
    fun `a lambda written inside a value form yields its own value`() {
        // A `yield` belongs to the innermost thing that is waiting for a value, so one written in a
        // lambda inside a `nya` block is the lambda's. Treating it as the `nya`'s would silently make
        // the block produce the wrong thing.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun add(n: int): ([int] -> int) {",
                "            return nya n > 0 {",
                "                yield { x: int -> yield x }",
                "            } else {",
                "                yield { x: int -> yield x + n }",
                "            }",
                "        }",
                "",
                "        fun main() {",
                "            println(add(3)(4))",
                "            println(add(0)(4))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("7\n4\n", output)
    }
}
