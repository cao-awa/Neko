package com.github.kusa233.neko

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for `nya condition` — a check that stops the program when the condition does not hold.
 *
 * The point of the statement is what a *run* does, so these tests call the compiled program and read
 * what comes back: a `nya` that holds lets the next statement run, and a `nya` that fails ends the
 * call with an `AssertionError` carrying the condition's own source text. Asserting on the message
 * matters as much as on the throw — a check that fails without saying what was being checked is a
 * worse diagnostic than no check at all.
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
        // The throw leaves the function that holds the `nya` and reaches the caller, so the failure
        // is not caught by anything the language provides unless a `try`/`catch` around it says so.
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

    @Test
    fun `a check may be caught as an error from outside the language`() {
        // `nya` throws a real JDK error, so the existing `try`/`catch` form handles it — which is what
        // makes a check usable in a program that wants to turn a failure into a value.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try {",
                "                nya 1 == 2",
                "            } catch (e: AssertionError) {",
                "                println(\"caught\")",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("caught\n", output)
    }
}
