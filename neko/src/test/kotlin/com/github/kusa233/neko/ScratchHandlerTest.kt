package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the `ScratchHandlerN` family that carries an `emit` clause's values.
 *
 * A function with an `emit` clause takes one of these interfaces as a hidden last parameter, so a
 * `scratch` calls `handler.dispatch(...)` instead of jumping to a `static` method found by
 * signature. The family is standard library source
 * (`com.github.kusa233.neko.stdlib.emitter.handler`), one interface per arity from zero to seven,
 * each declaring `dispatch(Object…): Object`.
 *
 * Two consequences drive everything here: the *arity* picks the interface, and the interface's
 * erased signature is why the values are boxed at the boundary and each site converts the answer
 * back to whatever it wanted.
 */
class ScratchHandlerTest {

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

    /**
     * A clause with no values uses `ScratchHandler0`, and `scratch` on its own enters it.
     *
     * The zero-arity interface exists for this: an effect that carries no information still needs a
     * handler, and writing `scratch` with nothing after it is the honest spelling.
     */
    @Test
    fun `an emit clause with no values uses ScratchHandler0`() {
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
                "            v: int = try Emitter.ping() { -> nyan 42 }",
                "            println(v)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("42\n", run(loaded))
    }

    /**
     * The clause's arity picks the interface, and the generated class implements exactly that one.
     *
     * A three-value clause needs `ScratchHandler3`; getting the count wrong would produce a class
     * that implements an interface it does not satisfy, which the JVM rejects at load time.
     */
    @Test
    fun `the clause's arity chooses the handler interface`() {
        val result = TestSupport.compile(
            program(
                "class Emitter {",
                "    static {",
                "        fun three(): int {",
                "            v: int = scratch 1, 2, 3",
                "            return v",
                "        } emit [int, int, int] -> int",
                "",
                "        fun two(): int {",
                "            v: int = scratch 1, 2",
                "            return v",
                "        } emit [int, int] -> int",
                "    }",
                "}",
            ),
            name = "ScratchHandler.neko",
        )
        assertTrue(!result.hasErrors, "the program must compile first: ${TestSupport.messages(result)}")

        val methods = methodDescriptors(result.classes.getValue("com/github/kusa233/neko/sample/Emitter"))
        assertEquals(
            "(Lcom/github/kusa233/neko/stdlib/emitter/handler/ScratchHandler3;)I",
            methods["three"],
            "three values need the three-argument interface",
        )
        assertEquals(
            "(Lcom/github/kusa233/neko/stdlib/emitter/handler/ScratchHandler2;)I",
            methods["two"],
            "two values need the two-argument interface",
        )
    }

    /**
     * The family stops at seven, because the library declares no wider interface.
     *
     * Eight values would need `ScratchHandler8`, which does not exist; reporting that here names the
     * limit instead of leaving a `NoClassDefFoundError` for run time.
     */
    @Test
    fun `a clause wider than the family allows is refused`() {
        val result = TestSupport.compile(
            program(
                "class Emitter {",
                "    static {",
                "        fun wide(): int {",
                "            v: int = scratch 1, 2, 3, 4, 5, 6, 7, 8",
                "            return v",
                "        } emit [int, int, int, int, int, int, int, int] -> int",
                "    }",
                "}",
            ),
            name = "ScratchHandler.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "at most 8 values" in it.message },
            "expected the width limit to be reported, but got: ${TestSupport.messages(result)}",
        )
    }

    /**
     * Two `try` blocks handing the same callee different handlers stay independent.
     *
     * This is the capability the redesign exists for. Each `try` builds its own handler object and
     * passes it, so the callee's `scratch` calls whichever one arrived — there is no program-wide
     * lookup that could confuse the two.
     */
    @Test
    fun `a callee sees whichever handler its caller passed`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun twice(): int {",
                "            a: int = scratch 1",
                "            b: int = scratch 2",
                "            return a + b",
                "        } emit [int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            doubled: int = try Emitter.twice() { x -> nyan x * 2 }",
                "            negated: int = try Emitter.twice() { x -> nyan 0 - x }",
                "            println(doubled)",
                "            println(negated)",
                "        }",
                "    }",
                "}",
            ),
        )
        // Both `scratch` sites in `twice` reach the same handler object, so the two answers are
        // computed by whichever handler the calling `try` supplied: (2+4) and (-1+-2).
        assertEquals("6\n-3\n", run(loaded))
    }

    private fun methodDescriptors(bytes: ByteArray): Map<String, String> {
        val methods = mutableMapOf<String, String>()
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    thrown: Array<out String>?,
                ): org.objectweb.asm.MethodVisitor? {
                    if (name != "<init>" && name != "<clinit>") methods[name] = descriptor
                    return null
                }
            },
            org.objectweb.asm.ClassReader.SKIP_CODE,
        )
        return methods
    }
}
