package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for `scratch`, `nyan`, `yield`, `emit` and `try`.
 *
 * The rule these pin down is the one the sample describes, and it is worth stating plainly because
 * it is easy to assume the old behaviour: **`scratch` does not throw.** A `try`'s `{ ... }` block is
 * compiled into a `static` method, and a `scratch` whose `emit` clause matches that handler calls it
 * with `invokestatic` and carries on with whatever it answers — so `v: int = scratch 1, 2` is an
 * ordinary initialisation whose right-hand side happens to be a call.
 *
 * Only two things transfer control:
 *
 *  * **`yield`** leaves through the `try` rather than through the handler, so it throws
 *    `EmitYieldException` carrying its value. The `try` catches it and keeps the value as the
 *    construct's own result, which is why a `try` used as a value needs a `yield` on the path where
 *    the call did not return normally.
 *  * **`nyan`** leaves the handler it is written in, answering a value to the `scratch` site that
 *    reached it. It is a plain `return` from the generated method.
 *
 * The two are not interchangeable, and the difference is the point: `scratch` 1 enters, `nyan`
 * answers the site that entered, `yield` answers the `try` that declared the handler.
 *
 * These programs `locate com.github.kusa233.neko.sample`, the same package as
 * `EffectRecovery.neko`, because that is where the sample puts them and a diagnostic quoting a file
 * should point at the code the sample actually shows.
 */
class EmitTest {

    /** The body of a program in the sample's package, given whole class declarations. */
    private fun samplePackage(vararg lines: String): String = buildString {
        append("locate com.github.kusa233.neko.sample\n")
        append('\n')
        for (line in lines) {
            append(line)
            append('\n')
        }
    }

    /**
     * The shape `EffectRecovery.neko` describes: `b` scratches, `nyanTest`'s handler answers, and
     * `a` forwards.
     *
     * It is the smallest program where the three keywords meet: `b` writes the `scratch`, `nyanTest`
     * writes the handler that receives it, and `a` sits in between so that the `scratch` runs two
     * frames below the `try` that declared the handler.
     */
    private fun recoveryProgram(): String = samplePackage(
        "class Emitter {",
        "    static {",
        "        fun nyanTest(): int {",
        "            v: int = try a() { a, b ->",
        "                if (a + b == 3) {",
        "                    nyan a + b",
        "                } else {",
        "                    yield 10",
        "                }",
        "            }",
        "            return v",
        "        }",
        "",
        "        fun a(): int {",
        "            return b()",
        "        } emit [int, int]",
        "",
        "        fun b(): int {",
        "            v: int = scratch 1, 2",
        "            return v * 10",
        "        } emit [int, int]",
        "    }",
        "}",
        "",
        "class Main {",
        "    static {",
        "        fun main() {",
        "            println(Emitter.nyanTest())",
        "        }",
        "    }",
        "}",
    )

    /** Compiles [source] and loads it. Nothing here needs the standard library any more. */
    private fun load(source: String): TestSupport.LoadedProgram =
        TestSupport.load(source, name = "Emit.neko")

    /** Runs `Main.main` and returns what it printed. */
    private fun run(program: TestSupport.LoadedProgram): String =
        program.capturingOutput { program.invoke("com.github.kusa233.neko.sample.Main", "main") }

    // ------------------------------------------------------------------ the whole mechanism

    @Test
    fun `a scratch reaches its handler and the nyan answer comes back`() {
        // `b` scratches 1 and 2, the handler answers 3, and `b` returns 30 — so the value crossed two
        // frames without an exception, which is what makes this a different mechanism from `raise`.
        assertEquals("30\n", run(load(recoveryProgram())))
    }

    @Test
    fun `the handler is a static method named after the function holding the try`() {
        val result = TestSupport.compile(recoveryProgram(), name = "Emit.neko")
        assertTrue(!result.hasErrors, "the program must compile first: ${TestSupport.messages(result)}")

        val methods = methodDescriptors(result.classes.getValue("com/github/kusa233/neko/sample/Emitter"))
        // The sample's own naming: `nyanTest$scratch$0`, taking the two `int`s of the `emit` clause
        // and answering the `int` the scratch site declared.
        assertEquals(
            "(II)I",
            methods["nyanTest\$scratch\$0"],
            "the handler should take its emit values and answer what the site declared",
        )
    }

    @Test
    fun `a scratch does not throw, so a yielding handler still leaves through the try`() {
        // `b` scratches 4 and 5, the sum is not 3, so the handler `yield`s 10 instead of answering —
        // and the value arrives at the `try` rather than back at `b`, which is why the answer is 10
        // and not 45.
        val program = load(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun nyanTest(): int {",
                "            v: int = try a() { a, b ->",
                "                if (a + b == 3) {",
                "                    nyan a + b",
                "                } else {",
                "                    yield 10",
                "                }",
                "            }",
                "            return v",
                "        }",
                "",
                "        fun a(): int {",
                "            return b()",
                "        } emit [int, int]",
                "",
                "        fun b(): int {",
                "            v: int = scratch 4, 5",
                "            return v * 10",
                "        } emit [int, int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Emitter.nyanTest())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("10\n", run(program), "the yield should answer the try, not the scratch")
    }

    @Test
    fun `a handler that answers nothing is declared to return void`() {
        // A `scratch` written as an expression statement of its own: nothing takes the value, so the
        // handler has no reason to answer one and is emitted as `void`.
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun fire(a: int, b: int) {",
                "            scratch a, b",
                "        } emit [int, int]",
                "",
                "        fun run() {",
                "            try fire(1, 2) { a, b ->",
                "                println(a + b)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(!result.hasErrors, "the program must compile first: ${TestSupport.messages(result)}")

        val methods = methodDescriptors(result.classes.getValue("com/github/kusa233/neko/sample/Emitter"))
        assertEquals(
            "(II)V",
            methods["run\$scratch\$0"],
            "a handler reached only by a discarded scratch should answer nothing",
        )
    }

    // -------------------------------------------------------------- how a scratch picks a handler

    @Test
    fun `a scratch reaches the handler whose emit signature matches`() {
        // Two handlers, different signatures: the `[int, int]` one is the one `b` matches, and the
        // `[string]` one is not. That is the whole linking rule, and it is why the signatures have to
        // be distinguishable.
        val program = load(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun run(): int {",
                "            v: int = try pair() { a, b ->",
                "                nyan a + b",
                "            }",
                "            return v",
                "        }",
                "",
                "        fun pair(): int {",
                "            return produce()",
                "        } emit [int, int]",
                "",
                "        fun produce(): int {",
                "            v: int = scratch 3, 4",
                "            return v",
                "        } emit [int, int]",
                "",
                "        fun words(): int {",
                "            w: int = scratch \"hi\"",
                "            return w",
                "        } emit [string]",
                "",
                "        fun talk(): int {",
                "            w: int = try say() { s ->",
                "                nyan 0",
                "            }",
                "            return w",
                "        }",
                "",
                "        fun say(): int {",
                "            return 0",
                "        } emit [string]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Emitter.run())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("7\n", run(program), "the [int, int] scratch should reach the [int, int] handler")
    }

    @Test
    fun `a scratch with no matching handler says so`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun lonely(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("no 'try' declares a handler") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `two handlers with the same emit signature are ambiguous`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun first(): int {",
                "            return 1",
                "        } emit [int]",
                "",
                "        fun second(): int {",
                "            return 2",
                "        } emit [int]",
                "",
                "        fun a(): int {",
                "            v: int = try first() { a ->",
                "                nyan a",
                "            }",
                "            return v",
                "        }",
                "",
                "        fun b(): int {",
                "            v: int = try second() { a ->",
                "                nyan a",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("could not tell the two apart") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a later scratch that disagrees about the handler's type is an error`() {
        // The first site takes an `int`, so the handler answers `int`; the second takes nothing, which
        // would need a `void` handler. One method cannot be both.
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun first(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun second() {",
                "            scratch 2",
                "        } emit [int]",
                "",
                "        fun holder(): int {",
                "            v: int = try source() { a ->",
                "                nyan a",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("so this one cannot take") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    // ------------------------------------------------------------------------------ nyan and yield

    @Test
    fun `nyan outside a try handler is refused`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun test(): int {",
                "            nyan 1",
                "            return 2",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("'nyan' can only be used") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `nyan that disagrees with what the sites take is an error`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun consumer(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun holder(): int {",
                "            v: int = try source() { a ->",
                "                nyan \"not an int\"",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("this 'nyan' answers") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a yield leaves through the try even when the handler is several calls deep`() {
        // The `yield` is written three calls below the `try` and still answers it — which is the one
        // thing `scratch` and `nyan` cannot do, and the reason `yield` is a throw. Each function in
        // between declares the same `emit` clause so it may pass the departure on unchanged.
        val program = load(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun inner(): int {",
                "            yield 42",
                "        } emit [int]",
                "",
                "        fun middle(): int {",
                "            return inner()",
                "        } emit [int]",
                "",
                "        fun outer(): int {",
                "            return middle()",
                "        } emit [int]",
                "",
                "        fun run(): int {",
                "            v: int = try outer() { a ->",
                "                nyan a",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Emitter.run())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("42\n", run(program), "a 'yield' three frames down should still answer its 'try'")
    }

    // ------------------------------------------------------------------------------- statements

    @Test
    fun `a scratch is not a departure, so the statements after it still run`() {
        // The counterpart of the old `raise` test. `scratch` is an ordinary call, so `helper` carries
        // on after it and `run` carries on after the value comes back — where a `raise` would have
        // left the function on the spot.
        val program = load(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun helper(v: int): int {",
                "            x: int = scratch v",
                "            println(\"helper continues\")",
                "            return x",
                "        } emit [int]",
                "",
                "        fun run() {",
                "            v: int = try helper(5) { a ->",
                "                nyan a",
                "            }",
                "            println(v)",
                "            println(\"after\")",
                "        }",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            Emitter.run()",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("helper continues\n5\nafter\n", run(program))
    }

    @Test
    fun `a statement after nyan is unreachable at error level`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun consumer(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun holder(): int {",
                "            v: int = try source() { a ->",
                "                nyan a",
                "                println(\"unreachable\")",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("unreachable statement") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    /**
     * An `emit` clause is worth what its signature says, not what its body happens to do.
     *
     * This used to be an error — "declares an 'emit' clause but can never reach a 'try' handler" —
     * and the check was retired because it enforced the wrong rule. Whether a function *can* leave by
     * scratching is a property of its declared signature, which is what a caller matches on; a body
     * that never scratches is simply a function that always answers normally, which is a perfectly
     * good implementation of a clause some other function may need.
     *
     * The value still comes back the ordinary way, through `return`, so the `try` that calls it gets
     * `7` rather than a scratch value.
     */
    @Test
    fun `a function with an emit clause need not scratch`() {
        val loaded = TestSupport.load(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun test(): int {",
                "            return 7",
                "        } emit [int]",
                "",
                "        fun main() {",
                "            v: int = try test() { x ->",
                "                nyan x",
                "            }",
                "            println(v)",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Emitter", "main")
        }
        assertEquals("7", printed.trim())
    }

    @Test
    fun `a scratch needs a function that declares an emit clause`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun test(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("declares an 'emit' clause") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a scratch must provide exactly the values its emit clause declares`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun test(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int, int]",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("must provide 2 value(s)") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a scratch value of the wrong type is reported at the value`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun test(): int {",
                "            v: int = scratch \"text\"",
                "            return v",
                "        } emit [int]",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("has type string") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    // ---------------------------------------------------------------------------------- the call

    @Test
    fun `a call to a scratchable function is only legal in a try`() {
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun test() {",
                "            scratch 1",
                "        } emit [int]",
                "",
                "        fun call() {",
                "            test()",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("call it as") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a wrapper that declares the same emit clause may call through`() {
        // `wrapper` writes no `scratch` at all: it forwards `source`'s clause unchanged, so the
        // departure belongs to `source` and passing it on needs no `try` of its own.
        val program = load(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun source(): int {",
                "            v: int = scratch 1",
                "            return v",
                "        } emit [int]",
                "",
                "        fun wrapper(): int {",
                "            return source()",
                "        } emit [int]",
                "",
                "        fun run(): int {",
                "            v: int = try wrapper() { a ->",
                "                nyan a",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Emitter.run())",
                "        }",
                "    }",
                "}",
            ),
        )
        // The handler answers `a`, which is the 1 `source` scratched, so the wrapper forwards it
        // through unchanged.
        assertEquals("1\n", run(program))
    }

    // ---------------------------------------------------------------------------- wide values

    @Test
    fun `an emit clause carries wide values without boxing them`() {
        // The old mechanism boxed every value into an `Object[]`; a `scratch` passes them as ordinary
        // parameters, so a `long` keeps its own descriptor and reaches the handler intact. This pins
        // the descriptor down, which is the part a reader would otherwise have to take on trust.
        val source = samplePackage(
            "class Emitter {",
            "    static {",
            "        fun source(): int {",
            "            return 1",
            "        } emit [long, double]",
            "",
            "        fun consumer(): int {",
            "            v: int = scratch 9000000000L, 2.5",
            "            return v",
            "        } emit [long, double]",
            "",
            "        fun run(): int {",
            "            v: int = try source() { a, b ->",
            "                nyan 7",
            "            }",
            "            return v",
            "        }",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source, name = "Emit.neko")
        assertTrue(!result.hasErrors, "the program must compile first: ${TestSupport.messages(result)}")

        val methods = methodDescriptors(
            result.classes.getValue("com/github/kusa233/neko/sample/Emitter"),
        )
        assertEquals(
            "(JD)I",
            methods["run\$scratch\$0"],
            "a wide value should keep its own slot width as a parameter",
        )
    }

    // ------------------------------------------------------------------------ the handler's class

    @Test
    fun `a handler is emitted in the class that holds its try, and scratch may call it from anywhere`() {
        // The handler lives on `Other`, because that is where the `try` was written, but the only
        // `scratch` reaching it is in `Emitter`. That is why it is emitted `public static` rather than
        // `private`: no other access flag would let a different class call it.
        val program = load(
            samplePackage(
                "class Other {",
                "    static {",
                "        fun run(): int {",
                "            v: int = try Emitter.consumer() { a ->",
                "                nyan a + 100",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
                "",
                "class Emitter {",
                "    static {",
                "        fun consumer(): int {",
                "            v: int = scratch 5",
                "            return v",
                "        } emit [int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Other.run())",
                "        }",
                "    }",
                "}",
            ),
        )
        // `consumer` scratches 5, which reaches `Other.run$scratch$0`, which answers 105 — and that
        // number comes back to `consumer` as its own `v`, then out of `run` as its `v`.
        assertEquals("105\n", run(program))
    }

    @Test
    fun `a function that declares an emit clause records the exception it can throw`() {
        // A `yield` written in a function that is *not* the one catching leaves through that
        // function's own frame, so it must be able to throw. The class file's `Exceptions` attribute
        // is where a caller — and a reflective reader — finds that.
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun inner(): int {",
                "            yield 42",
                "        } emit [int]",
                "",
                "        fun run(): int {",
                "            v: int = try inner() { a ->",
                "                nyan a",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(!result.hasErrors, "the program must compile first: ${TestSupport.messages(result)}")

        assertEquals(
            listOf(TestSupport.emitYieldExceptionClass()),
            thrownTypesOf(
                result.classes.getValue("com/github/kusa233/neko/sample/Emitter"),
                "inner",
            ),
            "a function whose 'emit' clause is left by a 'yield' must record it",
        )
    }

    @Test
    fun `a method that catches the yield itself declares nothing`() {
        // The mirror image, and the one worth being precise about: `run` wraps the throwing call in a
        // `try` that catches `EmitYieldException`, so the exception cannot leave `run` and the method
        // has nothing to declare. Only the method it *calls* is marked.
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static {",
                "        fun inner(): int {",
                "            yield 42",
                "        } emit [int]",
                "",
                "        fun run(): int {",
                "            v: int = try inner() { a ->",
                "                nyan a",
                "            }",
                "            return v",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(!result.hasErrors, "the program must compile first: ${TestSupport.messages(result)}")

        val methods = methodDescriptors(
            result.classes.getValue("com/github/kusa233/neko/sample/Emitter"),
        )
        assertEquals(
            emptyList(),
            thrownTypesOf(
                result.classes.getValue("com/github/kusa233/neko/sample/Emitter"),
                "run",
            ),
            "a 'try' catches the exception, so the catching method declares nothing",
        )
        // And the method that really throws does declare it, which is what makes the pair meaningful.
        assertEquals("()I", methods["inner"], "the fixture should still contain 'inner'")
    }

    @Test
    fun `a handler that answers with nyan declares nothing`() {
        // The mirror image: `nyanTest`'s handler only ever `nyan`s, and a `nyan` is a plain `return`
        // from the handler method — no exception is involved, so nothing is declared.
        val result = TestSupport.compile(recoveryProgram(), name = "Emit.neko")
        assertTrue(!result.hasErrors, "the program must compile first: ${TestSupport.messages(result)}")

        assertEquals(
            emptyList(),
            thrownTypesOf(
                result.classes.getValue("com/github/kusa233/neko/sample/Emitter"),
                "nyanTest",
            ),
            "'nyanTest' catches its own 'yield', so it declares nothing",
        )
    }


    /** The parameter/return descriptor of every method in [bytes], keyed by name. */
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

    /** The `Exceptions` attribute of the method named [method] in [bytes]. */
    private fun thrownTypesOf(bytes: ByteArray, method: String): List<String> {
        var thrown: List<String> = emptyList()
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): org.objectweb.asm.MethodVisitor? {
                    if (name == method) thrown = exceptions.orEmpty().toList()
                    return null
                }
            },
            org.objectweb.asm.ClassReader.SKIP_CODE,
        )
        return thrown
    }
}
