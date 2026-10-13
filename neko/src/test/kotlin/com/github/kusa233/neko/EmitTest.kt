package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for `raise`, `emit` and `try`.
 *
 * The rule is that a function declaring an `emit` clause hands its values back by *leaving early* —
 * it throws a carrier that the caller's `try` catches and unpacks. The generated code is inspected
 * where the shape matters (the builder's name and descriptor) and run where the behaviour matters,
 * because the point of the lowering is that the values survive the frame boundary intact.
 *
 * These programs `locate com.github.kusa233.neko.sample`, the same package as `ExceptionSample.neko`.
 * Neko resolves a name against the file's own package and without an `import`, so the standard
 * library `EarlyEmitException` and `EmitExtractorN` — which are themselves Neko sources under
 * `stdlib` — are only reachable from that package. Writing these fixtures anywhere else
 * would test a program whose generated code names classes it cannot see.
 *
 * A `try` comes in the two forms the language gives it. The *statement* form
 * `try f(10) { a, b -> ... }` discards what the call raises and is implemented by `EmitExtractorN`,
 * whose `invoke` answers nothing. The *expression* form `r: int = try f() { a -> yield 1 }` is worth
 * a value and is implemented by `EmitExtractorWithResultN`, whose `invoke` answers the `yield` — and
 * when the call returns normally instead, the `try` is worth the call's own return value.
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

    /** A raising `Emitter` and a `Main` that catches it, the shape the sample describes. */
    private fun raisingProgram(): String = samplePackage(
        "class Emitter {",
        "    static fun test(multiplier: int) {",
        "        raise 1 * multiplier, multiplier",
        "    } emit [int, int]",
        "}",
        "",
        "class Main {",
        "    static fun main() {",
        "        try Emitter.test(10) { first, second ->",
        "            println(first)",
        "            println(second)",
        "        }",
        "    }",
        "}",
    )

    /** Compiles [source] into a fresh directory together with the standard library, and loads it. */
    private fun loadWithSupport(source: String): TestSupport.LoadedProgram =
        TestSupport.loadWithLibrary(source, name = "Emit.neko")

    @Test
    fun `a raise compiles and hands both values to the extractor`() {
        val program = loadWithSupport(raisingProgram())
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        // `raise 1 * 10, 10` — the values cross the frame boundary by being thrown, and arrive in
        // the order the `raise` named them.
        assertEquals("10\n10\n", output)
    }

    @Test
    fun `a raising function names its builder after itself`() {
        val result = compileInSamplePackage(raisingProgram())
        assertTrue(!result.hasErrors, "the program must compile first")
        val methods = methodDescriptors(result.classes.getValue("com/github/kusa233/neko/sample/Emitter"))

        // The sample's own naming: `test$emit$0()`, `private static`, returning `Object[]` — and it
        // takes one `int` per raised value, so `raise 1 * multiplier, multiplier` gives it two.
        assertEquals(
            "(II)[Ljava/lang/Object;",
            methods["test\$emit\$0"],
            "the builder should box the raised values into an Object[]",
        )
    }

    @Test
    fun `a raising function records the exception it throws`() {
        val result = compileInSamplePackage(raisingProgram())
        // The spec's line 31: a function with an `emit` clause declares that it can raise.
        assertEquals(
            listOf("com/github/kusa233/neko/stdlib/emit/EarlyEmitException"),
            thrownTypesOf(result.classes.getValue("com/github/kusa233/neko/sample/Emitter"), "test"),
            "an 'emit' clause must reach the class file's Exceptions attribute",
        )
    }

    @Test
    fun `a function with an emit clause must actually raise`() {
        val result = TestSupport.compile(
            TestSupport.file(
                "class Emitter {",
                "    static fun test() {",
                "        println(\"no raise\")",
                "    } emit [int]",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("can never 'raise'") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a try needs a callee that can raise`() {
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun plain() {",
                "    }",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        try Emitter.plain() { ->",
                "            println(\"never\")",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("nothing to catch") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a raise may not leave a function without an emit clause`() {
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun test() {",
                "        raise 1",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("emit") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `the number of raised values must match the emit clause`() {
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun test() {",
                "        raise 1",
                "    } emit [int, int]",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("2") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `the statement after a raise is unreachable at error level`() {
        val source = TestSupport.file(
            "class Emitter {",
            "    static fun test() {",
            "        raise 1",
            "        println(\"unreachable\")",
            "    } emit [int]",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compileWithLibrary(source)).single()
        assertEquals(
            "unreachable statement: execution cannot continue past the preceding 'raise'",
            error.message,
        )
        assertEquals(
            TestSupport.locationOf(source, "println"),
            "${error.span?.line}:${error.span?.column}",
            "the error belongs on the unreachable statement",
        )
    }

    @Test
    fun `an emit clause may be combined with a return type`() {
        // A raiseable function may also answer a value on the path where it returns instead of
        // raising; the two are not exclusive. Which one the caller observed is what a `try`
        // expression resolves: the call's own value, or the extractor's `yield`.
        val program = loadWithSupport(
            samplePackage(
                "class Emitter {",
                "    static fun test(mode: int): int {",
                "        if (mode == 1) {",
                "            raise 9",
                "        }",
                "        return 3",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        returned: int = try Emitter.test(0) { value ->",
                "            yield value * 10",
                "        }",
                "        println(returned)",
                "        raised: int = try Emitter.test(1) { value ->",
                "            yield value * 10",
                "        }",
                "        println(raised)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        // The first call returns `3`, so the `try` is worth the call's own value and the extractor
        // never runs. The second `raise`s `9`, so the extractor's `yield 9 * 10` is what the `try`
        // is worth instead.
        assertEquals("3\n90\n", output, "the try should be worth whichever path was taken")
    }

    @Test
    fun `a yielding extractor implements the value-returning extractor family`() {
        val result = compileInSamplePackage(
            samplePackage(
                "class Emitter {",
                "    static fun test(): int {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        value: int = try Emitter.test() { raised ->",
                "            yield raised + 1",
                "        }",
                "        println(value)",
                "    }",
                "}",
            ),
        )
        assertTrue(!result.hasErrors, "the program must compile first")

        val extractor = result.classes.keys.single { it.endsWith("\$Emit\$0") }
        val methods = methodDescriptors(result.classes.getValue(extractor))
        assertEquals(
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            methods["invoke"],
            "the yielding invoke must answer the value, erased to Object",
        )
        assertTrue(
            interfacesOf(result.classes.getValue(extractor))
                .contains("com/github/kusa233/neko/stdlib/emit/EmitExtractorWithResult1"),
            "a yielding extractor must implement the value-returning family",
        )
    }

    @Test
    fun `an extractor that does not yield implements the void extractor family`() {
        val result = compileInSamplePackage(raisingProgram())
        val extractor = result.classes.keys.single { it.endsWith("\$Emit\$0") }
        assertEquals(
            "(Ljava/lang/Object;Ljava/lang/Object;)V",
            methodDescriptors(result.classes.getValue(extractor))["invoke"],
            "a statement-form extractor's invoke answers nothing",
        )
        assertTrue(
            interfacesOf(result.classes.getValue(extractor))
                .contains("com/github/kusa233/neko/stdlib/emit/EmitExtractor2"),
            "a non-yielding extractor must implement the void family",
        )
    }

    @Test
    fun `a try used as a value needs a yield`() {
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun test(): int {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        value: int = try Emitter.test() { raised ->",
                "            println(raised)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("must 'yield'") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a yield may not appear in the extractor of a try statement`() {
        // The statement form discards the value, so a `yield` there would produce something no one
        // could ever read.
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun test(): int {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        try Emitter.test() { raised ->",
                "            yield raised",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("cannot 'yield'") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a return may not appear inside a try extractor`() {
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun test(): int {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        value: int = try Emitter.test() { raised ->",
                "            return raised",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("'return' cannot be used inside") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a call to a raiseable function is a plain call when the caller declares the same emit clause`() {
        // A wrapper that declares the same clause has nothing to do with the values: they are
        // already in the form its own caller expects, so letting the exception travel one frame
        // further is the same as catching it and raising it again. Requiring a `try` here would
        // force every wrapper to unpack values only to hand them straight back.
        val program = loadWithSupport(
            samplePackage(
                "class Emitter {",
                "    static fun inner(multiplier: int) {",
                "        raise 2 * multiplier, multiplier",
                "    } emit [int, int]",
                "",
                "    static fun outer(multiplier: int) {",
                "        inner(multiplier)",
                "    } emit [int, int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        try Emitter.outer(10) { first, second ->",
                "            println(first)",
                "            println(second)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("20\n10\n", output, "the raise should pass through the wrapper untouched")
    }

    @Test
    fun `a passing-through wrapper records that it may raise`() {
        val result = compileInSamplePackage(
            samplePackage(
                "class Emitter {",
                "    static fun inner() {",
                "        raise 1",
                "    } emit [int]",
                "",
                "    static fun outer() {",
                "        inner()",
                "    } emit [int]",
                "}",
            ),
        )
        assertTrue(!result.hasErrors, "unexpected messages: ${TestSupport.messages(result)}")
        // `outer` never writes `raise` itself, but the exception it forwards still leaves through it,
        // so its caller must be able to catch it there.
        assertEquals(
            listOf("com/github/kusa233/neko/stdlib/emit/EarlyEmitException"),
            thrownTypesOf(result.classes.getValue("com/github/kusa233/neko/sample/Emitter"), "outer"),
            "a wrapper must declare the exception it forwards",
        )
    }

    @Test
    fun `a call still needs a try when the emit clauses differ`() {
        // `emit [int]` forwarding into `emit [Object]` is not a pass-through: it would need a
        // conversion, and a `try` is where that belongs.
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun inner() {",
                "        raise 1",
                "    } emit [int]",
                "",
                "    static fun outer() {",
                "        inner()",
                "    } emit [Object]",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("never returns a value") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a call to a raiseable function needs a try when the caller cannot raise`() {
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun inner() {",
                "        raise 1",
                "    } emit [int]",
                "",
                "    static fun outer() {",
                "        inner()",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("never returns a value") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a try used as a value works inside a lambda`() {
        // The extractor becomes a class of its own, emitted inside whichever method it appears in. If
        // that method is a lambda, whatever the extractor reads from the enclosing scope has to be
        // forwarded by the lambda first — exactly as a nested lambda's reads are.
        val program = loadWithSupport(
            samplePackage(
                "class Emitter {",
                "    static fun test(): int {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        prefix: int = 100",
                "        read: [int] -> int = { extra ->",
                "            yield try Emitter.test() { raised ->",
                "                yield prefix + raised + extra",
                "            }",
                "        }",
                "        println(read(1))",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("106\n", output, "the extractor must see what the lambda captured")
    }

    @Test
    fun `a try used as a value carries a string`() {
        val program = loadWithSupport(
            samplePackage(
                "class Emitter {",
                "    static fun test(): string {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        label: string = try Emitter.test() { raised ->",
                "            yield \"raised\"",
                "        }",
                "        println(label)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("raised\n", output, "the value must survive as a string")
    }

    @Test
    fun `a try used as a value needs the standard library just as a raise does`() {
        // The value form names `EmitExtractorWithResultN` in the class it generates, so a program
        // without the library must be told at compile time rather than at load time.
        val result = TestSupport.compile(
            samplePackage(
                "class Emitter {",
                "    static fun test(): int {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        value: int = try Emitter.test() { raised ->",
                "            yield raised",
                "        }",
                "    }",
                "}",
            ),
            name = "Emit.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("standard library") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a try used as a value rejects an extractor that yields the wrong type`() {
        val result = TestSupport.compileWithLibrary(
            TestSupport.file(
                "class Emitter {",
                "    static fun test(): int {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        value: int = try Emitter.test() { raised ->",
                "            yield \"not an int\"",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("when the call returns") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a raise still works when the extractor reads an outer variable`() {
        // The extractor is a separate class, so anything it reads from the enclosing method has to
        // be captured — the same rule a lambda follows.
        val program = loadWithSupport(
            samplePackage(
                "class Emitter {",
                "    static fun test() {",
                "        raise 5",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        prefix: int = 100",
                "        try Emitter.test() { value ->",
                "            println(prefix + value)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("105\n", output, "the extractor should see the enclosing method's variable")
    }

    @Test
    fun `a raise leaves the function rather than merely returning a value`() {
        // `raise` transfers control out, so the statement after it never runs. Here the body is
        // written so the raise is conditional: only the branch taken raises, and the code after the
        // `if` still runs otherwise — which is what proves the lowering is a real departure and not
        // an ordinary return the caller could ignore.
        val program = loadWithSupport(
            samplePackage(
                "class Emitter {",
                "    static fun test(mode: int) {",
                "        if (mode == 1) {",
                "            raise 7",
                "        }",
                "        println(\"no raise\")",
                "    } emit [int]",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        try Emitter.test(0) { value ->",
                "            println(value)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("no raise\n", output, "a call that does not raise should not run the extractor")
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

    /** The internal names of the interfaces [bytes] declares. */
    private fun interfacesOf(bytes: ByteArray): List<String> {
        var interfaces: List<String> = emptyList()
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    declared: Array<out String>?,
                ) {
                    interfaces = declared.orEmpty().toList()
                }
            },
            org.objectweb.asm.ClassReader.SKIP_CODE,
        )
        return interfaces
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

    /**
     * Compiles [source] in the sample's package together with the standard library.
     *
     * The library has to be part of the same run: `raise` is lowered against its
     * `EarlyEmitException` and `EmitExtractor`, so a program that raises without them is rejected as
     * missing the library rather than compiled.
     */
    private fun compileInSamplePackage(source: String): CompilationResult =
        TestSupport.compileWithLibrary(source, name = "Emit.neko")
}
