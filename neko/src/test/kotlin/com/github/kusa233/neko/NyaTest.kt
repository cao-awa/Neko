package com.github.kusa233.neko

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for `nya condition` — a check that says what a failure means.
 *
 * The point of the construct is what a *run* does, so these tests call the compiled program and read
 * what comes back. It has three forms, and each is tested for its own behaviour:
 *
 *  * `nya a == b` — a failed check ends the call with an `AssertionError` carrying the condition's own
 *    source text. Asserting on the message matters as much as on the throw: a check that fails without
 *    saying what was being checked is a worse diagnostic than no check at all.
 *  * `nya a == b { ... }` — the block handles the failure and **control carries on after it**, which is
 *    what replaced `try`/`catch` as the way a program recovers. It is entered two ways: a condition
 *    that came back false, and an `AssertionError` thrown while the condition was being evaluated —
 *    so `nya method() { ... }` catches a failed check inside `method`, and the condition need not be a
 *    `bool` at all when a block says what failing means.
 *  * `nya a == b { ... } else { ... }` — worth a value, one block per outcome of the condition.
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

    // ------------------------------------------------- the block as a catcher

    @Test
    fun `a failure block answers a check that threw inside the condition`() {
        // The second way into the block. `method` ends in a failed check of its own, and a bare `nya`
        // throws — so without the catcher the call would unwind instead of reaching the block.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun method() {",
                "            nya 1 == 2",
                "        }",
                "",
                "        fun main() {",
                "            nya method() {",
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
    fun `a block catches a check that failed deeper down the call`() {
        // The error travels out of `inner` and through `outer` to the block that answers it, so the
        // catcher is about the call failing, not about where in the call the check was written.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun inner() {",
                "            nya false",
                "        }",
                "",
                "        fun outer() {",
                "            inner()",
                "        }",
                "",
                "        fun main() {",
                "            nya outer() {",
                "                println(\"caught\")",
                "            }",
                "            println(\"done\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("caught\ndone\n", output)
    }

    @Test
    fun `a block may take a condition that answers nothing`() {
        // `void` cannot be false, so there is no first way in — the block can only ever mean "if this
        // fails". That is why a condition with a block is not required to be a `bool`.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun close() {",
                "            println(\"closing\")",
                "        }",
                "",
                "        fun main() {",
                "            nya close() {",
                "                println(\"failed\")",
                "            }",
                "            println(\"done\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        // The call succeeded, so only its own output and the code after it are printed.
        assertEquals("closing\ndone\n", output)
    }

    @Test
    fun `a block takes a condition of any other type too`() {
        // An `int` answering condition is a compile error for a bare `nya` and fine with a block: the
        // block is only ever entered by a throw, so the value the call produced is simply dropped.
        val lines = file(
            "class Main {",
            "    static {",
            "        fun count(): int {",
            "            return 7",
            "        }",
            "",
            "        fun main() {",
            "            nya count() {",
            "                println(\"failed\")",
            "            }",
            "            println(\"done\")",
            "        }",
            "    }",
            "}",
        )
        assertTrue(
            TestSupport.errors(TestSupport.compile(lines)).isEmpty(),
            "a block makes any condition type legal",
        )
        val program = TestSupport.load(lines)
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        // The call succeeded, so only the code after it is printed.
        assertEquals("done\n", output)
    }

    @Test
    fun `a failed check in a value form's condition takes the failure block`() {
        // The value form is entered the same two ways, and the answer still comes from the block that
        // ran: a throw is the failure reading, so it yields what the failure block yields.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun method(): int {",
                "            nya false",
                "            return 1",
                "        }",
                "",
                "        fun main() {",
                "            v: int = nya method() {",
                "                yield 0",
                "            } else {",
                "                yield 1",
                "            }",
                "            println(v)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("0\n", output)
    }

    @Test
    fun `a throw from somewhere else is not caught`() {
        // The catcher is for a failed check, which is what the block describes. Anything else on its
        // way out is not this construct's to answer, so it keeps travelling: dividing by zero is an
        // `ArithmeticException`, and the block must not swallow it.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun divide(n: int): int {",
                "            return 1 / n",
                "        }",
                "",
                "        fun main() {",
                "            nya divide(0) {",
                "                println(\"caught\")",
                "            }",
                "            println(\"done\")",
                "        }",
                "    }",
                "}",
            ),
        )
        // The output and the throwable are both wanted here, and [printedBeforeFailure] swallows the
        // throw, so the capture is written out for this one case.
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        val error = try {
            program.invoke("com.github.kusa233.test.Main", "main")
            null
        } catch (failure: Throwable) {
            failure
        } finally {
            System.setOut(original)
        }
        val output = buffer.toString(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals("", output, "only an AssertionError should reach the block")
        assertTrue(
            error is ArithmeticException,
            "the block must not catch another throwable, got $error",
        )
    }

    @Test
    fun `a failed check inside a failure block is not caught by that block`() {
        // The catcher covers the condition and nothing else, so a `nya` written inside the block is
        // the block's own problem — otherwise the block would catch its own failure and run twice.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            nya 1 == 2 {",
                "                println(\"first\")",
                "                nya 1 == 2",
                "                println(\"second\")",
                "            }",
                "            println(\"done\")",
                "        }",
                "    }",
                "}",
            ),
        )
        var output = ""
        assertionErrorFrom { output = printedBeforeFailure { program.invoke("com.github.kusa233.test.Main", "main") } }
        // The block ran once and its own failed check escaped: neither `second` nor `done` is reached.
        assertEquals("first\n", output)
    }

    @Test
    fun `a failed check inside an else block is not caught either`() {
        // The other side of the same boundary. The condition held, so the handler is already behind
        // us; a `nya` written in the `else` block is that block's own failure and must escape.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            v: int = nya 1 == 1 {",
                "                yield 0",
                "            } else {",
                "                println(\"in else\")",
                "                yield 1",
                "            }",
                "            println(v)",
                "            v2: int = nya 1 == 1 {",
                "                yield 0",
                "            } else {",
                "                nya 1 == 2",
                "                yield 2",
                "            }",
                "            println(v2)",
                "        }",
                "    }",
                "}",
            ),
        )
        var output = ""
        assertionErrorFrom { output = printedBeforeFailure { program.invoke("com.github.kusa233.test.Main", "main") } }
        // The first construct answered 1, and the `else`-block check that failed ended the call rather
        // than being swallowed by the handler it had already passed.
        assertEquals("in else\n1\n", output)
    }

    @Test
    fun `a failure block does not trust what the throw path could break`() {
        // The soundness rule, and the reason the block is not simply "the false reading". `h == null`
        // failing proves `h` is there — but only if the check ran to an answer. `ready()` can throw
        // instead, and it gets to the same block with `h` still null, so reading `h.value` there is
        // exactly the null dereference the analyzer exists to prevent.
        val result = TestSupport.compile(
            file(
                "class Holder {",
                "    val value: int = 4",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun ready(): bool {",
                "            return true",
                "        }",
                "",
                "        fun main() {",
                "            h: Holder? = null",
                "            nya h == null || ready() {",
                "                println(h.value)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("nullable") },
            "the block must not assume a fact the throw path never proved: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a failure block still narrows on a condition that cannot throw`() {
        // The other half of the rule: with nothing in the condition that can throw, a false answer is
        // the only way in, so the false reading's facts are sound and the block may use them.
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
                "            nya h == null {",
                "                println(h.value == null)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("false\n", output)
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
    fun `a bare check compiles to a branch past the throw, the way assert does`() {
        // The requirement is on the *shape*, so this reads the emitted bytecode rather than running
        // it. Java's own `assert` branches past its throw; the opposite nesting — branch to the
        // throw, then jump over it — would need a `goto` so that nothing at all happens when the
        // check passes, which is what a construct written throughout a program cannot afford.
        //
        // The condition is a bare `bool` variable rather than a comparison: a comparison materialises
        // its answer through a `goto` of its own, and that instruction belongs to the comparison
        // rather than to the check, so it would make this assertion read the wrong thing.
        assertEquals(
            listOf(
                "ICONST_1",
                "ISTORE",
                "ILOAD",
                "IFNE", // past the throw: the assert shape
                "NEW",
                "DUP",
                "LDC",
                "INVOKESPECIAL",
                "ATHROW",
            ),
            instructionsOf("            flag: bool = true", "            nya flag"),
            "a bare check is a load, a branch past the throw, and the throw itself",
        )
    }

    @Test
    fun `a check with a block keeps the branch to the failure and the jump over it`() {
        // The counterpart: a block gives the two paths somewhere to meet, so it keeps the branch to
        // the failure and the jump over it, and the caught error is dropped where it landed. The
        // condition is again a bare `bool` so nothing but the check contributes instructions, which
        // is what makes the exact sequence assertable.
        assertEquals(
            listOf(
                "ICONST_0",
                "ISTORE",
                "ILOAD",
                "IFEQ", // → the failure block
                "GOTO", // over it, when the check passed
                "POP", // the handler drops the caught AssertionError
                "ICONST_1",
                "ISTORE", // the failure block's body
                "GOTO", // → end
            ),
            instructionsOf(
                "            flag: bool = false",
                "            nya flag {",
                "                flag = true",
                "            }",
            ),
            "a block form branches to the failure and jumps past it",
        )
    }

    @Test
    fun `a check that passes carries on and a failed one names its condition`() {
        // The two runs the assert shape has to get right: the passing path costs the branch and
        // nothing else, and the failing path still reports the condition's own text. A shape change
        // that inverted the branch would swap these two and this catches it.
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            nya 1 == 1",
                "            println(\"after pass\")",
                "            nya 1 == 2",
                "            println(\"after failure\")",
                "        }",
                "    }",
                "}",
            ),
        )
        // The output and the throwable are both wanted, and [printedBeforeFailure] swallows the
        // throw, so the capture is written out for this one case.
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        val error = try {
            program.invoke("com.github.kusa233.test.Main", "main")
            null
        } catch (failure: Throwable) {
            failure
        } finally {
            System.setOut(original)
        }
        val output = buffer.toString(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals("after pass\n", output, "the first check passes and the second does not")
        assertTrue(error is AssertionError, "the second check should have thrown, got $error")
        assertEquals("1 == 2", error.message, "the message is the condition's own source text")
    }

    /**
     * The instructions of the `main` method [bodyLines] produce, in order, by opcode name.
     *
     * A shape assertion cannot be made by calling the program: a `goto` that lands on the next
     * instruction runs exactly like no `goto` at all. Reading the instructions is the only way to
     * tell the two nestings apart, so the class is compiled here and walked with ASM.
     *
     * Every visit hook is recorded rather than only `visitInsn`, because the instructions that
     * matter most here arrive through `visitVarInsn` and `visitTypeInsn` — a visitor that skipped
     * them would make the expected lists lie by omission. The method's own `RETURN` and the static
     * initialiser's following `INVOKESTATIC` are dropped, so the assertion stays about the check
     * rather than about how the method happens to end.
     */
    private fun instructionsOf(vararg bodyLines: String): List<String> {
        val program = TestSupport.load(
            file(
                "class Main {",
                "    static {",
                "        fun main() {",
                *bodyLines,
                "        }",
                "    }",
                "}",
            ),
        )
        val bytes = java.nio.file.Files.readAllBytes(
            program.directory.resolve("com/github/kusa233/test/Main.class"),
        )
        val names = mutableListOf<String>()
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): org.objectweb.asm.MethodVisitor? {
                    if (name != "main") return null
                    return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                        override fun visitInsn(opcode: Int) {
                            names += OPCODE_NAMES.getValue(opcode)
                        }

                        override fun visitVarInsn(opcode: Int, index: Int) {
                            names += OPCODE_NAMES.getValue(opcode)
                        }

                        override fun visitTypeInsn(opcode: Int, type: String?) {
                            names += OPCODE_NAMES.getValue(opcode)
                        }

                        override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label?) {
                            names += OPCODE_NAMES.getValue(opcode)
                        }

                        override fun visitLdcInsn(value: Any?) {
                            names += "LDC"
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            names += OPCODE_NAMES.getValue(opcode)
                        }
                    }
                }
            },
            0,
        )
        // The generated method closes with `RETURN`, and the static initialiser that ran it with an
        // `INVOKESTATIC`; both are the method's ending rather than anything about the check.
        return names.dropLastWhile { it == "RETURN" || it == "INVOKESTATIC" }
    }

    /**
     * The opcode names these tests assert on, mapped from the constants ASM reports.
     *
     * Named rather than numeric so a failure reads `IFNE` instead of `154`: the assertion says what
     * it is about, and a reader comparing the expected list with the documentation above can tell at
     * a glance whether the branch is the one the shape calls for.
     */
    private companion object {
        val OPCODE_NAMES: Map<Int, String> = mapOf(
            Opcodes.ICONST_0 to "ICONST_0",
            Opcodes.ICONST_1 to "ICONST_1",
            Opcodes.ISTORE to "ISTORE",
            Opcodes.ILOAD to "ILOAD",
            Opcodes.IFEQ to "IFEQ",
            Opcodes.IFNE to "IFNE",
            Opcodes.GOTO to "GOTO",
            Opcodes.POP to "POP",
            Opcodes.NEW to "NEW",
            Opcodes.DUP to "DUP",
            Opcodes.ATHROW to "ATHROW",
            Opcodes.INVOKESPECIAL to "INVOKESPECIAL",
            Opcodes.INVOKESTATIC to "INVOKESTATIC",
            Opcodes.INVOKEVIRTUAL to "INVOKEVIRTUAL",
            Opcodes.RETURN to "RETURN",
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
