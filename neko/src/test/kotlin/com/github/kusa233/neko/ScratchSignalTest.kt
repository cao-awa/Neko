package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for `try pre: label`, `blep: label Signal { ... }` and `scratch ... signal Signal`.
 *
 * A `blep` group is one handler answering several operations. The group is opened by
 * `try pre: label`, each `blep` adds a branch keyed on an enum constant, and the `try` that closes
 * the group — `try call(...) { ... }: label` — supplies the default. Code generation turns that into
 * a single `dispatch` whose body is a chain of `if (signal == CONST)` tests, with the closing `try`'s
 * own body as the `else`.
 *
 * The `signal` half of a `scratch` is what selects a branch. It is optional: with none, the value
 * passed is `null`, which matches no branch, so the group answers with its default body. That is the
 * property these tests pin down — `signal` is additive, and every `scratch` written before it existed
 * keeps behaving as it did.
 */
class ScratchSignalTest {

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
     * The whole feature end to end: three `blep` branches and a default.
     *
     * The clauses answer `int`, the group is installed by a `try` whose body `nyan`s `100`, and the
     * three sites name `ADD`, `MULTIPLY` and `MINUS`. Each site gets its own branch's answer, which is
     * what makes one handler serve three operations.
     */
    @Test
    fun `a blep group answers each signal with its own branch`() {
        val loaded = TestSupport.load(
            program(
                "miao class Signal {",
                "    ADD,",
                "    MULTIPLY,",
                "    MINUS,",
                "}",
                "",
                "class Emitter {",
                "    static {",
                "        fun probe() {",
                "            add: int = scratch 10, 5 signal Signal.ADD",
                "            println(add)",
                "            mul: int = scratch 10, 10 signal Signal.MULTIPLY",
                "            println(mul)",
                "            sub: int = scratch 10, 5 signal Signal.MINUS",
                "            println(sub)",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try pre: label",
                "            blep: label Signal.ADD { a, b ->",
                "                nyan a + b",
                "            }",
                "            blep: label Signal.MULTIPLY { a, b ->",
                "                nyan a * b",
                "            }",
                "            blep: label Signal.MINUS { a, b ->",
                "                nyan a - b",
                "            }",
                "            try Emitter.probe() { a, b ->",
                "                nyan 100",
                "            }: label",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("15\n100\n5\n", run(loaded))
    }

    /**
     * A `scratch` with no `signal` reaches the group's default body.
     *
     * This is what keeps the keyword additive. The same handler serves both kinds of site: a named
     * signal picks a branch, and no signal at all — `null` — matches nothing, so control falls through
     * to the `{ ... }` the closing `try` wrote.
     */
    @Test
    fun `a scratch with no signal gets the default branch`() {
        val loaded = TestSupport.load(
            program(
                "miao class Signal {",
                "    ADD,",
                "}",
                "",
                "class Emitter {",
                "    static {",
                "        fun probe() {",
                "            named: int = scratch 1, 2 signal Signal.ADD",
                "            println(named)",
                "            plain: int = scratch 1, 2",
                "            println(plain)",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try pre: label",
                "            blep: label Signal.ADD { a, b ->",
                "                nyan a + b",
                "            }",
                "            try Emitter.probe() { a, b ->",
                "                nyan 7",
                "            }: label",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("3\n7\n", run(loaded))
    }

    /**
     * The generated `dispatch` takes the signal as its last parameter, typed `java.lang.Enum`.
     *
     * The interface family is what fixes this: `ScratchHandlerN.dispatch(Object…, Enum)` is the one
     * shape every generated handler implements, so the descriptor is not free to vary with the
     * body.
     */
    @Test
    fun `the generated dispatch takes the signal as a trailing Enum`() {
        val result = TestSupport.compile(
            program(
                "miao class Signal {",
                "    ADD,",
                "}",
                "",
                "class Emitter {",
                "    static {",
                "        fun probe() {",
                "            v: int = scratch 1, 2 signal Signal.ADD",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try pre: label",
                "            blep: label Signal.ADD { a, b ->",
                "                nyan a + b",
                "            }",
                "            try Emitter.probe() { a, b ->",
                "                nyan 0",
                "            }: label",
                "        }",
                "    }",
                "}",
            ),
            name = "Signal.neko",
        )
        assertTrue(!result.hasErrors, "the program must compile first: ${TestSupport.messages(result)}")

        val handlerClass = result.classes.keys.firstOrNull { it.contains("\$ScratchHandler\$") }
            ?: error("no handler class was generated: ${result.classes.keys}")
        assertEquals(
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Enum;)Ljava/lang/Object;",
            methodDescriptors(result.classes.getValue(handlerClass))["dispatch"],
            "the handler takes the signal after the clause's values",
        )
    }

    /**
     * A `blep` signal that is not an enum constant is refused.
     *
     * The generated branch is an identity comparison against the constant, so a plain value would
     * build a branch no site could ever reach. Saying so at the signal names the rule; leaving it
     * would leave a handler that silently answers its default every time.
     */
    @Test
    fun `a blep signal must name an enum constant`() {
        val result = TestSupport.compile(
            program(
                "class Emitter {",
                "    static {",
                "        fun probe() {",
                "            v: int = scratch 1, 2",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try pre: label",
                "            blep: label 42 { a, b ->",
                "                nyan a + b",
                "            }",
                "            try Emitter.probe() { a, b ->",
                "                nyan 0",
                "            }: label",
                "        }",
                "    }",
                "}",
            ),
            name = "Signal.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "must name an enum constant" in it.message },
            "expected the signal rule to be reported, but got: ${TestSupport.messages(result)}",
        )
    }

    /**
     * The same rule guards a `scratch`'s signal, which is what a site says to pick a branch.
     */
    @Test
    fun `a scratch signal must name an enum constant`() {
        val result = TestSupport.compile(
            program(
                "class Emitter {",
                "    static {",
                "        fun probe() {",
                "            v: int = scratch 1, 2 signal 42",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try pre: label",
                "            blep: label 1 { a, b ->",
                "                nyan a + b",
                "            }",
                "            try Emitter.probe() { a, b ->",
                "                nyan 0",
                "            }: label",
                "        }",
                "    }",
                "}",
            ),
            name = "Signal.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "must name an enum constant" in it.message },
            "expected the scratch's signal to be checked, but got: ${TestSupport.messages(result)}",
        )
    }

    /**
     * A `blep` naming a group that was never opened is refused.
     *
     * The group is the link between the branches and the `try` that installs them; a typo in the name
     * would otherwise produce a branch that is never emitted, and the `try` would be short one answer.
     */
    @Test
    fun `a blep naming an unknown group is refused`() {
        val result = TestSupport.compile(
            program(
                "miao class Signal {",
                "    ADD,",
                "}",
                "",
                "class Emitter {",
                "    static {",
                "        fun probe() {",
                "            v: int = scratch 1, 2",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            blep: label Signal.ADD { a, b ->",
                "                nyan a + b",
                "            }",
                "        }",
                "    }",
                "}",
            ),
            name = "Signal.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "no handler group named" in it.message },
            "expected the unknown group to be reported, but got: ${TestSupport.messages(result)}",
        )
    }

    /**
     * A group opened but never installed is refused.
     *
     * Its branches would never run, and the `try` they were meant to extend would be an ordinary
     * handler — so a `scratch` naming a signal would silently take that handler's answer instead of
     * the branch's. Reported where the group is opened, which is the line to fix.
     */
    @Test
    fun `a group that is never installed is refused`() {
        val result = TestSupport.compile(
            program(
                "miao class Signal {",
                "    ADD,",
                "}",
                "",
                "class Emitter {",
                "    static {",
                "        fun probe() {",
                "            v: int = scratch 1, 2 signal Signal.ADD",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try pre: label",
                "            blep: label Signal.ADD { a, b ->",
                "                nyan a + b",
                "            }",
                "            try Emitter.probe() { a, b ->",
                "                nyan 0",
                "            }",
                "        }",
                "    }",
                "}",
            ),
            name = "Signal.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "opened but never installed" in it.message },
            "expected the unused group to be reported, but got: ${TestSupport.messages(result)}",
        )
    }

    /**
     * A branch whose `nyan` disagrees with the clause is refused, just as in a plain handler.
     *
     * Every branch of a group answers the same `emit` clause, so one that answered a different type
     * would answer a call it is serving with something the site cannot use.
     */
    @Test
    fun `a branch that answers the wrong type is refused`() {
        val result = TestSupport.compile(
            program(
                "miao class Signal {",
                "    ADD,",
                "}",
                "",
                "class Emitter {",
                "    static {",
                "        fun probe() {",
                "            v: int = scratch 1, 2 signal Signal.ADD",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try pre: label",
                "            blep: label Signal.ADD { a, b ->",
                "                nyan \"not an int\"",
                "            }",
                "            try Emitter.probe() { a, b ->",
                "                nyan 0",
                "            }: label",
                "        }",
                "    }",
                "}",
            ),
            name = "Signal.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "'nyan' answers" in it.message },
            "expected the branch's answer to be checked, but got: ${TestSupport.messages(result)}",
        )
    }

    /**
     * A bare call names the nearest declaration, even when another class in the file declares the
     * same name with an `emit` clause.
     *
     * This is the `Test.neko` shape: `main` calls `a()`, and one class in the file declares
     * `a() emit [int, int] -> int` while this one declares a plain `a()`. Looking the name up across
     * the whole file first would pick the `emit` one if it happened to be declared earlier, and the
     * ordinary call would then be refused for not being a `try` — a report about a call that is not
     * the one written.
     */
    @Test
    fun `a bare call resolves to its own class, not another class's emit function`() {
        val result = TestSupport.compileWithLibrary(
            program(
                "class Emitter {",
                "    static {",
                "        fun a() {",
                "            v: int = scratch 1, 2",
                "            println(v)",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a()",
                "        }",
                "",
                "        fun a() {",
                "            println(\"plain\")",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            !result.hasErrors,
            "the call names Main's own 'a', which declares no 'emit' clause, but got: " +
                TestSupport.messages(result),
        )
    }

    /**
     * The same rule the other way around: an `emit` function of another class is still callable when
     * the enclosing class declares no `a`, and firing a `scratch` inside it still needs a `try`.
     */
    @Test
    fun `another class's emit function is still reached when this class declares none`() {
        val result = TestSupport.compileWithLibrary(
            program(
                "class Emitter {",
                "    static {",
                "        fun a() {",
                "            v: int = scratch 1, 2",
                "            println(v)",
                "        } emit [int, int] -> int",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a()",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { "'a' declares an 'emit' clause" in it.message },
            "expected the rule to apply to a call it actually binds to, but got: " +
                TestSupport.messages(result),
        )
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
