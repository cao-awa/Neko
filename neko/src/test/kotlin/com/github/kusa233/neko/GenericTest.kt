package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generics: `class Box<T>`, `fun <T> f(value: T)`, `Box<int>(1)` and `f<int>(1)`.
 *
 * Neko erases type parameters, exactly as the JVM does: inside a body `T` *is* `Object`, so a
 * parameter of type `T` accepts any reference and a body may only hand it on, print it, or store it.
 * That is not a shortcut — it is the same rule the standard library's forwarders already depend on,
 * and the reason `EmitExtractorN`'s bodies can be written at all.
 *
 * What these tests pin down is therefore the part that is *not* erasure: writing `<...>` at a call
 * site is legal, it is checked against what the callee declares, and it never changes the descriptor
 * the call compiles to.
 */
class GenericTest {

    private fun program(vararg lines: String): String = buildString {
        append("locate com.github.kusa233.neko.sample\n")
        append('\n')
        for (line in lines) {
            append(line)
            append('\n')
        }
    }

    private fun run(loaded: TestSupport.LoadedProgram, className: String): String =
        loaded.capturingOutput {
            loaded.invoke(className, "main")
        }

    /**
     * The shape of `InstanceSample.neko`: a generic `meow class`, explicit type arguments, and a
     * derived `equals` comparing the boxed field.
     *
     * Two boxes built from the same value are equal and one built from a different value is not,
     * which is what the sample prints. That works through type arguments being erased: the field is
     * `Object`, the value is a boxed `Integer`, and `equals` compares those.
     */
    @Test
    fun `a generic meow class answers equality from its boxed field`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            var1: Instance = Instance<int>(123)",
                "            var2: Instance = Instance<int>(123)",
                "            var3: Instance = Instance<int>(456)",
                "            println(var1 == var2)",
                "            println(var2 == var3)",
                "        }",
                "    }",
                "}",
                "",
                "meow class Instance<T>(val data: T) {",
                "}",
            ),
        )
        assertEquals("true\nfalse\n", run(loaded, "com.github.kusa233.neko.sample.Main"))
    }

    /**
     * A generic function takes a type parameter, is called with one written, and prints its value.
     *
     * `T` is `Object` in the body, so `println(value)` is the only thing such a body can usefully do
     * without a cast — which is exactly what the sample does.
     */
    @Test
    fun `a generic function is callable with and without written type arguments`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            methodGeneric<int>(123)",
                "            methodGeneric(\"no argument written\")",
                "        }",
                "",
                "        fun <T> methodGeneric(value: T) {",
                "            println(value)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "123\nno argument written\n",
            run(loaded, "com.github.kusa233.neko.sample.Main"),
        )
    }

    /**
     * Type arguments change nothing about the descriptor — that is what erasure *means*.
     *
     * `Box<int>` and `Box<string>` compile to the same constructor, and the generic class records its
     * type parameter so a reader still sees the name.
     */
    @Test
    fun `type arguments are erased and the class still records its parameter`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a: Box = Box<int>(1)",
                "            b: Box = Box<string>(\"x\")",
                "            println(a == b)",
                "        }",
                "    }",
                "}",
                "",
                "meow class Box<T>(val data: T) {",
                "}",
            ),
            name = "Generic.neko",
        )
        assertTrue(!result.hasErrors, "expected a clean compile: ${TestSupport.messages(result)}")

        val classSignature = classSignatureOf(
            result.classes.getValue("com/github/kusa233/neko/sample/Box"),
        )
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;", classSignature)
    }

    /**
     * A generic function records its parameter in the `Signature` attribute, and keeps the erased
     * descriptor.
     *
     * Without the attribute `fun <T> f(value: T)` and `fun f(value: Object)` would be the same class
     * file, even though the source says something different. The descriptor on the right is the point
     * of the distinction: `(TT;)` names the variable, `(Ljava/lang/Object;)` is what the JVM calls.
     */
    @Test
    fun `a generic function records its type parameter in the signature attribute`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            id<int>(1)",
                "        }",
                "",
                "        fun <T> id(value: T): T {",
                "            return value",
                "        }",
                "    }",
                "}",
            ),
            name = "Generic.neko",
        )
        assertTrue(!result.hasErrors, "expected a clean compile: ${TestSupport.messages(result)}")

        val methods = methodsOf(result.classes.getValue("com/github/kusa233/neko/sample/Main"))
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", methods.getValue("id").first)
        assertEquals("<T:Ljava/lang/Object;>(TT;)TT;", methods.getValue("id").second)
    }

    /**
     * Writing the wrong number of type arguments is refused, at each of the three places one may be
     * written: a construction, a bare call, and a member call.
     *
     * The count is the only thing there is to check — after erasure every parameter is `Object`, so
     * every type argument fits. A silent acceptance would let `Box<int, int>` read as if it meant
     * something.
     */
    @Test
    fun `the number of type arguments must match the declaration`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            Box<int, int>(1)",
                "            id<int, int>(1)",
                "            plain<int>(1)",
                "        }",
                "",
                "        fun <T> id(value: T): T { return value }",
                "        fun plain(value: int) { println(value) }",
                "    }",
                "}",
                "",
                "meow class Box<T>(val data: T) {",
                "}",
            ),
            name = "Generic.neko",
        )
        val messages = TestSupport.messages(result)
        assertTrue(
            messages.any { "'Box' declares 1 type parameter (T) but 2 type arguments" in it },
            "expected the construction to be checked: $messages",
        )
        assertTrue(
            messages.any { "'id' declares 1 type parameter (T) but 2 type arguments" in it },
            "expected the call to be checked: $messages",
        )
        assertTrue(
            messages.any { "'plain' declares no type parameters but 1 type argument" in it },
            "expected a non-generic callee to be checked: $messages",
        )
    }

    /**
     * A class's type parameter is only in scope inside that class.
     *
     * `T` at the top level of another class is not a type, so it is reported as unresolved rather
     * than silently becoming `Object` — which would make a typo look like a working erasure.
     */
    @Test
    fun `a class type parameter is not in scope in another class`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            x: T = 1",
                "            println(x)",
                "        }",
                "    }",
                "}",
                "",
                "meow class Box<T>(val data: T) {",
                "}",
            ),
            name = "Generic.neko",
        )
        // The proof that `T` did *not* become `Object` here: an `Object` target accepts an `int`
        // (that is what `Box<int>(1)` relies on), so a clean compile would mean the name leaked.
        val messages = TestSupport.messages(result)
        assertTrue(
            messages.any { "declared as T" in it },
            "expected 'T' outside the class to stay an unknown type: $messages",
        )
    }

    /**
     * `<` is still the relational operator when it is not followed by a call.
     *
     * The parser decides between type arguments and a comparison by looking ahead: only a `<...>`
     * that closes into `(` is a type argument list. Without that rule every `a < b` would try to
     * parse `b` as a type.
     */
    @Test
    fun `a comparison is not mistaken for type arguments`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a: int = 1",
                "            b: int = 2",
                "            println(a < b)",
                "            println(a > b)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals("true\nfalse\n", run(loaded, "com.github.kusa233.neko.sample.Main"))
    }

    /**
     * A duplicate type parameter is refused where it is written.
     *
     * Two names in one list would declare the same variable twice, and the second could never be
     * referred to separately — so the declaration is the only place a fix is available.
     */
    @Test
    fun `a duplicate type parameter is refused`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(1)",
                "        }",
                "    }",
                "}",
                "",
                "meow class Box<T, T>(val data: T) {",
                "}",
            ),
            name = "Generic.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "duplicate type parameter 'T'" in it.message },
            "expected the duplicate to be reported: ${TestSupport.messages(result)}",
        )
    }

    /**
     * One function's type parameter does not leak into the next function's body.
     *
     * The declarations are walked in one loop, so the names have to be installed per function and
     * taken down again. If they were left installed, `second` below would compile — reading `T` as
     * `Object` — even though nothing in its own declaration introduced it.
     */
    @Test
    fun `a type parameter does not leak into a sibling function`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() { println(1) }",
                "",
                "        fun <T> first(value: T) { println(value) }",
                "",
                "        fun second() {",
                "            x: T = 1",
                "            println(x)",
                "        }",
                "    }",
                "}",
            ),
            name = "Generic.neko",
        )
        val messages = TestSupport.messages(result)
        assertTrue(
            messages.any { "declared as T" in it },
            "expected 'T' not to leak out of 'first': $messages",
        )
    }

    // ------------------------------------------------------------ type arguments at run time

    /**
     * `value::generic` reports the classes a value was built with, through erasure.
     *
     * This is the whole point of the operator: the JVM erased `int`, but the construction site knew
     * it, so the object carries it and this reads it back. `int` prints as `int` and `string` as
     * `class java.lang.String`, which is `Class.toString` — the same text `int.class` produces.
     */
    @Test
    fun `type arguments survive to run time`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a: Instance = Instance<int>(123)",
                "            b: Instance = Instance<string>(\"hi\")",
                "            println(a::generic[0])",
                "            println(b::generic[0])",
                "        }",
                "    }",
                "}",
                "",
                "meow class Instance<T>(val data: T) {",
                "}",
            ),
        )
        assertEquals(
            "int\nclass java.lang.String\n",
            run(loaded, "com.github.kusa233.neko.sample.Main"),
        )
    }

    /**
     * The result is always an array, so a two-parameter class reads the same way.
     *
     * One rule for every arity: `Pair<int, string>::generic[0]` and `[1]`. A single-parameter class
     * answering a bare `Class` would have made the two spellings different for no reason.
     */
    @Test
    fun `a two parameter class reports both arguments in order`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            p: Pair = Pair<int, string>(1, \"a\")",
                "            println(p::generic[0])",
                "            println(p::generic[1])",
                "        }",
                "    }",
                "}",
                "",
                "meow class Pair<A, B>(val first: A, val second: B) {",
                "}",
            ),
        )
        assertEquals(
            "int\nclass java.lang.String\n",
            run(loaded, "com.github.kusa233.neko.sample.Main"),
        )
    }

    /**
     * The type arguments take part in the derived `equals`, `hashCode` and `toString`.
     *
     * Two values whose fields are identical but whose arguments differ are different values: the
     * field is `Object`, so `Instance<int>(123)` and `Instance<string>(123)` hold the same boxed
     * `Integer` and would compare equal on the fields alone. The last line pins `toString`, which is
     * what a reader uses to tell them apart in output.
     */
    @Test
    fun `type arguments take part in the derived object methods`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            same1: Instance = Instance<int>(123)",
                "            same2: Instance = Instance<int>(123)",
                "            other: Instance = Instance<string>(123)",
                "            println(same1 == same2)",
                "            println(same2 == other)",
                "            println(same1)",
                "        }",
                "    }",
                "}",
                "",
                "meow class Instance<T>(val data: T) {",
                "}",
            ),
        )
        assertEquals(
            "true\nfalse\nInstance(data=123, generic=[int])\n",
            run(loaded, "com.github.kusa233.neko.sample.Main"),
        )
    }

    /**
     * A generic class cannot be built without naming its arguments.
     *
     * The instance carries them, so there is nothing to carry when none were written — and a value
     * that answered "no arguments" to `::generic` would be lying about how it was made. Requiring
     * them turns the omission into a message at the construction.
     */
    @Test
    fun `constructing a generic class without type arguments is refused`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            b: Box = Box(1)",
                "            println(b)",
                "        }",
                "    }",
                "}",
                "",
                "meow class Box<T>(val data: T) {",
                "}",
            ),
            name = "Generic.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "'Box' is generic, so its type arguments have to be written" in it.message },
            "expected the missing arguments to be reported: ${TestSupport.messages(result)}",
        )
    }

    /** `::generic` on a value whose class declares no type parameters has nothing to report. */
    @Test
    fun `applying generic access to a non-generic class is refused`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            p: Plain = Plain(1)",
                "            println(p::generic)",
                "        }",
                "    }",
                "}",
                "",
                "meow class Plain(val data: int) {",
                "}",
            ),
            name = "Generic.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "declares no type parameters" in it.message },
            "expected the non-generic receiver to be reported: ${TestSupport.messages(result)}",
        )
    }

    /** `::` names only `generic`, so anything else is reported at the name that was written. */
    @Test
    fun `an unknown name after double colon is refused`() {
        val result = TestSupport.compile(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            b: Box = Box<int>(1)",
                "            println(b::other)",
                "        }",
                "    }",
                "}",
                "",
                "meow class Box<T>(val data: T) {",
                "}",
            ),
            name = "Generic.neko",
        )
        assertTrue(
            TestSupport.errors(result).any { "the only name it takes is 'generic'" in it.message },
            "expected the unknown member to be reported: ${TestSupport.messages(result)}",
        )
    }

    /** The `Signature` attribute of the class in [bytes], or `null` when it has none. */
    private fun classSignatureOf(bytes: ByteArray): String? {
        var signature: String? = null
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    genericSignature: String?,
                    superName: String,
                    interfaces: Array<out String>?,
                ) {
                    signature = genericSignature
                }
            },
            org.objectweb.asm.ClassReader.SKIP_CODE,
        )
        return signature
    }

    /**
     * The descriptor and `Signature` attribute of every method in [bytes], keyed by name, excluding
     * the constructor and the static initializer.
     */
    private fun methodsOf(bytes: ByteArray): Map<String, Pair<String, String?>> {
        val methods = mutableMapOf<String, Pair<String, String?>>()
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    thrown: Array<out String>?,
                ): org.objectweb.asm.MethodVisitor? {
                    if (name != "<init>" && name != "<clinit>") {
                        methods[name] = descriptor to signature
                    }
                    return null
                }
            },
            org.objectweb.asm.ClassReader.SKIP_CODE,
        )
        return methods
    }
}
