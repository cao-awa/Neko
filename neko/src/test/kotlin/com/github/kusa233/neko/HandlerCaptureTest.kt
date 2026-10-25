package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for a `try` handler reading locals from the scope it was written in.
 *
 * A handler body becomes a method of a generated class, so an enclosing local is not simply in scope
 * there — it has to be copied into a field and passed through the constructor, exactly as a lambda
 * captures. Before the handler became a value, the block was a `static` method, which meant an
 * enclosing local was silently unreachable: the compiler did not report it and the read produced the
 * type's default instead.
 *
 * The capture list is computed from the body's references, so these tests also pin the traversal: a
 * `nyan` or a `scratch` value that names an enclosing local has to be collected like any other
 * expression, and missing it compiles the reference as a `0`.
 */
class HandlerCaptureTest {

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

    /** The plain case: a handler reads a local declared in the function holding the `try`. */
    @Test
    fun `a handler reads a local from the enclosing function`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 2, 3",
                "            return v",
                "        } emit [int, int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            factor: int = 10",
                "            r: int = try Emitter.source() { a, b ->",
                "                nyan a * b * factor",
                "            }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("60\n", run(loaded))
    }

    /**
     * A capture named in a `nyan` value is collected too.
     *
     * This is the shape a missing traversal arm breaks silently: the body is emitted the same way, but
     * a reference the capture list missed compiles to the type's default, so the answer would be `0`
     * rather than `10`. Worth its own test because "it compiled and ran" is not the same as "it read
     * the variable".
     */
    @Test
    fun `a capture named in a nyan value is collected`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 2, 3",
                "            return v",
                "        } emit [int, int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            answer: int = 10",
                "            r: int = try Emitter.source() { a, b ->",
                "                nyan answer",
                "            }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("10\n", run(loaded))
    }

    /**
     * A `scratch` written *inside* a handler body reaches the handler of the function around it.
     *
     * That handler has to travel into the generated class as well, because the class is a frame of
     * its own. Without it the inner site would have no object to call and would quietly produce the
     * type's default — the shape this test exists to catch, since the value it names is a captured
     * local and the answer travels two layers out.
     *
     * The trace: `main` calls `holder` with a handler answering `100`; `holder` calls `target` with
     * the block below; `target` scratches `1`, so that block runs; the block scratches the captured
     * `payload` (`7`), which reaches `main`'s handler and answers `100`; the block then answers
     * `100 + 1`, which is what `target`'s site takes.
     */
    @Test
    fun `a scratch inside a handler uses the enclosing function's handler`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun target(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun holder(): int {",
                "            payload: int = 7",
                "            r: int = try target() { x ->",
                "                w: int = scratch payload",
                "                nyan w + x",
                "            }",
                "            return r",
                "        } emit [int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            r: int = try Emitter.holder() { y -> nyan 100 }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("101\n", run(loaded))
    }

    /** Several captures keep their own values, whatever order they are first read in. */
    @Test
    fun `several captures keep their own values`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            label: string = \"sum\"",
                "            scale: int = 3",
                "            offset: int = 100",
                "            r: int = try Emitter.source() { x ->",
                "                println(label)",
                "                nyan x * scale + offset",
                "            }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("sum\n103\n", run(loaded))
    }

    /**
     * A handler inside a lambda captures through both layers.
     *
     * The lambda forwards whatever the handler needs, because the value has to travel from the
     * function where it was declared to the handler's method. The filter that decides which symbol is
     * a capture compares against the *innermost* method boundary, so a local declared inside the
     * lambda is captured by the handler and not mistaken for one of its own.
     */
    @Test
    fun `a handler inside a lambda reaches both scopes`() {
        val loaded = TestSupport.load(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            outer: int = 100",
                "            f: [int] -> int = { seal ->",
                "                inner: int = 7",
                "                yield try Emitter.source() { x ->",
                "                    nyan outer + inner + x",
                "                }",
                "            }",
                "            println(f(1))",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("108\n", run(loaded))
    }

    /**
     * Writing a captured local from inside a handler is refused.
     *
     * The copy lives in a field, so the write would change the copy and leave the original alone.
     * That is the same trade a lambda makes, and the diagnostic says which construct is responsible.
     */
    @Test
    fun `writing a captured local inside a handler is refused`() {
        val result = TestSupport.compile(
            program(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            counter: int = 0",
                "            r: int = try Emitter.source() { x ->",
                "                counter = counter + 1",
                "                nyan counter",
                "            }",
                "            println(r)",
                "        }",
                "    }",
                "}",
            ),
            name = "Capture.neko",
        )
        assertTrue(
            TestSupport.errors(result).any {
                "cannot assign to 'counter' inside a 'try' handler" in it.message
            },
            "expected the capture write to be refused, but got: ${TestSupport.messages(result)}",
        )
    }

    /**
     * `this` is refused inside a handler.
     *
     * The handler runs on an object of its own, so `this` would name that object and not the one the
     * enclosing function was called on. Allowing it would compile to a field access on the wrong
     * class.
     */
    @Test
    fun `this is refused inside a handler`() {
        val result = TestSupport.compile(
            program(
                "class Emitter {",
                "    val bonus: int = 5",
                "",
                "    fun source(): int {",
                "        v: int = scratch 1",
                "        return v",
                "    } emit [int]",
                "",
                "    fun run() {",
                "        r: int = try source() { x ->",
                "            nyan this.bonus + x",
                "        }",
                "        println(r)",
                "    }",
                "}",
            ),
            name = "Capture.neko",
        )
        assertTrue(
            TestSupport.errors(result).any {
                "'this' cannot be used inside a 'try' handler" in it.message
            },
            "expected 'this' to be refused, but got: ${TestSupport.messages(result)}",
        )
    }
}
