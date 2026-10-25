package com.github.kusa233.neko

import com.github.kusa233.neko.diagnostics.Severity
import com.github.kusa233.neko.source.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Choosing an overload of a platform method.
 *
 * The rule that is easy to get wrong — and was wrong — is how a **primitive** argument is matched
 * against a **reference** parameter. `Thread` declares `sleep(long)`, `sleep(long, int)` and
 * `sleep(Duration)`, and reflection returns them in *no fixed order*: JDK 21 lists `sleep(Duration)`
 * first, JDK 25 lists `sleep(long)` first. While a reference parameter accepted any argument at all,
 * `Thread.sleep(1L)` resolved to `sleep(Duration)` on one JDK and `sleep(long)` on the other — the
 * same program, a different descriptor.
 *
 * That was not merely a wrong choice of method. The emitter pushed a `long` where a `Duration` was
 * declared, so the class writer's stack-map computation no longer matched the code and died inside
 * ASM with `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0` — an "internal
 * compiler error", from a ten-line program, on one JDK only. The tests below pin the rule so the
 * crash cannot come back by way of reflection's ordering.
 *
 * Note that `sleep(1)` is deliberately an **error**: Neko has no implicit numeric widening, so an
 * `int` is not an argument for a `long` parameter. A test asserts the diagnostic names the real
 * overloads, because the reader's next move is to write `1L`.
 */
class PlatformOverloadTest {

    /** Compiles [body] as the sole statement of a `main`, and returns the analysis result. */
    private fun compile(body: String): CompilationResult = Compiler.compileProgram(
        listOf(
            SourceFile.of(
                "P.neko",
                buildString {
                    append("locate t\n\nclass P {\n    static {\n        fun main() {\n")
                    append(body)
                    append("\n        }\n    }\n}\n")
                },
            ),
        ),
    )

    private fun errors(result: CompilationResult): List<String> =
        result.diagnostics.filter { it.severity == Severity.ERROR }.map { it.message }

    /** The `invokestatic` descriptors the generator produced for `main()`, in order. */
    private fun invokedDescriptors(body: String): List<String> {
        val classBytes = compile(body).classes.getValue("t/P")
        return InvocationRecorder.descriptorsOf(classBytes, "main", parameterCount = 0)
    }

    @Test
    fun `a long argument selects the long overload, not a reference one`() {
        // The regression: on JDK 21 this used to select `sleep(Duration)` and emit `(Ljava/time/Duration;)V`
        // after pushing a `long`, which killed the class writer.
        val descriptors = invokedDescriptors("Thread.sleep(1L)")

        assertTrue(
            descriptors.any { it.endsWith("Thread.sleep:(J)V") },
            "expected sleep(long) to be called, but the emitted calls were $descriptors",
        )
    }

    @Test
    fun `a long argument inside a loop still compiles`() {
        // The shape that surfaced the bug: alone the wrong descriptor was survivable, because ASM only
        // computes frames across a jump. In a loop it aborted the compile.
        val result = compile(
            "            for (i: int = 0; i < 3; i++) {\n                Thread.sleep(1L)\n            }",
        )

        assertEquals(emptyList(), errors(result))
    }

    @Test
    fun `an int argument is refused rather than widened`() {
        // `sleep(1)` must not quietly become `sleep(long)`: Neko widens nothing implicitly, and the
        // message has to list the overloads so the reader knows to write `1L`.
        val messages = errors(compile("Thread.sleep(1)"))

        assertEquals(1, messages.size, "expected exactly one error, got $messages")
        assertTrue("has no overload taking (int)" in messages.single(), messages.single())
        assertTrue("(long)" in messages.single(), "the real overloads should be listed: ${messages.single()}")
    }

    @Test
    fun `a primitive may be passed as its own box`() {
        // The other side of the rule: `Integer` really is what an `int` becomes, so this must still
        // resolve. A rule of "primitives never match references" would break every JDK method that
        // takes an `Object`, which is most of them.
        val result = compile("            o: Object = 1\n            java.lang.String.valueOf(1)")

        assertEquals(emptyList(), errors(result))
    }

    @Test
    fun `a reference argument still matches a reference parameter`() {
        // Non-regression for the ordinary case the old rule existed to serve: a reference value
        // reaching a reference parameter, with no conversion at all. `Arrays.asList` takes an
        // `Object[]`, so passing a `string` there must keep resolving — a rule of "only exact classes"
        // would reject it and break every JDK method taking `Object`.
        val result = compile("            s: string = \"abc\"\n            println(java.lang.String.valueOf(s))")

        assertEquals(emptyList(), errors(result))
    }
}
