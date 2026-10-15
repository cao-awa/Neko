package com.github.kusa233.neko

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for `meow class` — a class whose fields are declared in its header and whose `equals` and
 * `hashCode` are derived from them.
 *
 * The two rules that shape every test here are that *all* fields participate, including `private`
 * ones, and that `==` has to reach the derived `equals`: Neko has no cast and no `is`, so a
 * comparison that did not dispatch to the generated method would leave it unreachable from any
 * program, and equality could not be observed at all.
 *
 * The tests compile and run real programs rather than inspecting the tree, because the derived
 * methods exist only as bytecode — there is no body to assert on. The two exceptions are the
 * "a hand-written method wins" cases, where the *absence* of an emitted method is the property under
 * test and the class file is the only place that shows it.
 */
class MeowClassTest {

    /**
     * A `Neko` class with one field and a `main` that prints the results of the [comparisons] given.
     *
     * The comparisons are spelled as whole `println(...)` calls so a test can write exactly the
     * expression it is about.
     */
    private fun meowProgram(vararg comparisons: String): String = TestSupport.file(
        "meow class Neko(val data: int) {",
        "}",
        "",
        "class Main {",
        "    static {",
        "        fun main() {",
        "            a: Neko = Neko(1)",
        "            b: Neko = Neko(1)",
        "            c: Neko = Neko(2)",
        *comparisons,
        "        }",
        "    }",
        "}",
    )

    @Test
    fun `a single field decides equality`() {
        val program = TestSupport.load(
            meowProgram(
                "println(a == b)",
                "println(a == c)",
                "println(a != b)",
                "println(a != c)",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals(
            "true\nfalse\nfalse\ntrue\n",
            output,
            "'!=' must be the negation of '==', not a separate comparison",
        )
    }

    @Test
    fun `every field participates in equality`() {
        // Three fields, one differing at a time. A generated `equals` that compared only the first
        // field would pass the first case and fail the rest.
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Point(val x: int, val y: int, val z: int) {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            origin: Point = Point(0, 0, 0)",
                "            println(origin == Point(0, 0, 0))",
                "            println(origin == Point(1, 0, 0))",
                "            println(origin == Point(0, 1, 0))",
                "            println(origin == Point(0, 0, 1))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("true\nfalse\nfalse\nfalse\n", output)
    }

    @Test
    fun `a private field participates in equality`() {
        // `private` is a visibility, not a statement about identity: two points that differ only in
        // their private field are different points, exactly as they are in a Kotlin data class.
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Point(val x: int, private val y: int) {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a: Point = Point(1, 1)",
                "            println(a == Point(1, 1))",
                "            println(a == Point(1, 2))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("true\nfalse\n", output)
    }

    @Test
    fun `a var field participates in equality`() {
        // The field list takes `val` or `var`, and the derived comparison has to read whichever was
        // written — which it does through the field, so the two spellings behave alike.
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Counter(var count: int, val name: string) {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a: Counter = Counter(1, \"a\")",
                "            println(a == Counter(1, \"a\"))",
                "            println(a == Counter(2, \"a\"))",
                "            println(a == Counter(1, \"b\"))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("true\nfalse\nfalse\n", output)
    }

    @Test
    fun `a meow class with no fields is equal to another with no fields`() {
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Unit() {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Unit() == Unit())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("true\n", output)
    }

    @Test
    fun `equal instances have equal hash codes and unequal ones usually differ`() {
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Neko(val data: int, val name: string) {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a: Neko = Neko(1, \"a\")",
                "            b: Neko = Neko(1, \"a\")",
                "            println(a == b)",
                "            println(a.hashCode() == b.hashCode())",
                "            println(a.hashCode() == Neko(2, \"a\").hashCode())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        // The last line is a property of the 31-fold the numbers happen to produce, not a rule of
        // the language: two different instances are merely very likely to hash differently, and this
        // pair does. The rule the test pins down is on the second line.
        assertEquals("true\ntrue\nfalse\n", output)
    }

    @Test
    fun `equals and hashCode are callable from Neko source`() {
        val program = TestSupport.load(
            meowProgram(
                "println(a.equals(b))",
                "println(a.equals(c))",
                "println(a.hashCode() == b.hashCode())",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("true\nfalse\ntrue\n", output)
    }

    @Test
    fun `equals answers false for null and for a different class`() {
        // `equals` takes an `Object`, so it can be handed things that are not this class at all. Both
        // are answered rather than thrown, which is what makes the method safe to call.
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Neko(val data: int) {",
                "}",
                "",
                "class Other {",
                "    val data: int = 1",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a: Neko = Neko(1)",
                "            println(a.equals(a))",
                "            println(a.equals(Other()))",
                "            println(a.equals(\"Neko\"))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("true\nfalse\nfalse\n", output)
    }

    @Test
    fun `a hand-written equals is not replaced by the generated one`() {
        // The author's `equals` says every pair is equal. If the derived one had been emitted too,
        // the JVM would reject the class file for the duplicate method; if it had been *kept* and the
        // author's dropped, this program would print `false`.
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Neko(val data: int) {",
                "    fun equals(other: Object): bool {",
                "        return true",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Neko(1) == Neko(2))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("true\n", output, "the hand-written 'equals' should be the one '==' reaches")
        assertEquals(
            listOf("(Ljava/lang/Object;)Z"),
            methodDescriptors(program, "equals"),
            "only the author's 'equals' should be in the class file",
        )
    }

    @Test
    fun `a hand-written hashCode is not replaced by the generated one`() {
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Neko(val data: int) {",
                "    fun hashCode(): int {",
                "        return 42",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Neko(7).hashCode())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("42\n", output, "the hand-written 'hashCode' should be the one called")
        assertEquals(
            listOf("()I"),
            methodDescriptors(program, "hashCode"),
            "only the author's 'hashCode' should be in the class file",
        )
    }

    @Test
    fun `a meow interface is rejected`() {
        // The header is written, so the analyzer is the one that sees the declaration for what it
        // is; the field list is parsed first and satisfies the parser's own requirement.
        val source = TestSupport.file("meow interface Neko() {", "}")
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("'meow' cannot declare an interface"),
            "the error should say what 'meow' cannot declare, but was: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "Neko"),
            "${error.span?.line}:${error.span?.column}",
        )
    }

    @Test
    fun `a meow interface without a field list is still rejected`() {
        // A `meow interface` written with no parentheses breaks two rules at once. The parser's is
        // the one reported, because the declaration never reaches the analyzer as a meow class —
        // which is why the message talks about the missing list rather than about the interface.
        val source = TestSupport.file("meow interface Neko {", "}")
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("must list its fields"),
            "the error should say what is missing, but was: ${error.message}",
        )
    }

    @Test
    fun `a meow class with no parameter list is rejected`() {
        val source = TestSupport.file("meow class Neko {", "}")
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("must list its fields"),
            "the error should say what is missing, but was: ${error.message}",
        )
    }

    @Test
    fun `a plain class is unaffected by the meow rules`() {
        // `class Point()` has no header fields, so no `equals` is derived for it and its class file
        // is exactly what it was before `meow` existed. Asserted on the class file rather than by
        // running `a == b`: comparison of two plain class values is unchanged by this feature, and
        // what it does — reach `String.equals` — is a pre-existing defect that would only make this
        // test fail for a reason that is not about `meow`.
        val program = TestSupport.load(
            TestSupport.file(
                "class Point() {",
                "    val x: int = 1",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Point().x)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(emptyList(), methodDescriptors(program, "equals", className = "Point"))
        assertEquals(emptyList(), methodDescriptors(program, "hashCode", className = "Point"))
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("1\n", output)
    }

    @Test
    fun `the generated methods have the shapes Object declares`() {
        // The derived methods override `java.lang.Object`'s, and the JVM only accepts an override if
        // the descriptor matches exactly — `(Ljava/lang/Object;)Z`, `()I` and `()Ljava/lang/String;`.
        val program = TestSupport.load(TestSupport.file("meow class Neko(val data: int) {", "}"))
        assertEquals(
            listOf("(Ljava/lang/Object;)Z"),
            methodDescriptors(program, "equals"),
        )
        assertEquals(listOf("()I"), methodDescriptors(program, "hashCode"))
        assertEquals(listOf("()Ljava/lang/String;"), methodDescriptors(program, "toString"))
    }

    @Test
    fun `toString prints the class name and every field in declaration order`() {
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Cat(val age: int, val name: string) {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Cat(1, \"cat-name\"))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("Cat(age=1, name=\"cat-name\")\n", output)
    }

    @Test
    fun `toString prints every primitive type the way its wrapper does`() {
        // The `int`/`bool`/`char` and wide cases take different `append` overloads, and a `char` is
        // the one where the wrong choice is invisible in the descriptor: `append(int)` would print
        // `120` where `append(char)` prints `x`.
        val program = TestSupport.load(
            TestSupport.file(
                "meow class All(",
                "    val b: bool,",
                "    val c: char,",
                "    val y: byte,",
                "    val s: short,",
                "    val i: int,",
                "    val l: long,",
                "    val f: float,",
                "    val d: double",
                ") {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(All(true, 'x', 1, 2, 3, 4L, 1.5f, 2.5))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("All(b=true, c=x, y=1, s=2, i=3, l=4, f=1.5, d=2.5)\n", output)
    }

    @Test
    fun `toString of a meow class holding no fields is just the class name`() {
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Empty() {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Empty())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("Empty()\n", output)
    }

    @Test
    fun `toString of a meow class holding another one nests`() {
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Owner(val name: string) {",
                "}",
                "",
                "meow class Cat(val age: int, val owner: Owner) {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Cat(1, Owner(\"kusa\")))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("Cat(age=1, owner=Owner(name=\"kusa\"))\n", output)
    }

    @Test
    fun `toString is callable from Neko source`() {
        // Reached through the language rather than through the JVM's own `println`, which is what
        // proves the method is a member of the class as far as the analyzer is concerned.
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Cat(val age: int) {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            c: Cat = Cat(3)",
                "            println(c.toString())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("Cat(age=3)\n", output)
    }

    @Test
    fun `a hand-written toString is not replaced by the generated one`() {
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Neko(val data: int) {",
                "    fun toString(): string {",
                "        return \"mine\"",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Neko(1))",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("mine\n", output, "the hand-written 'toString' should be the one called")
        assertEquals(
            listOf("()Ljava/lang/String;"),
            methodDescriptors(program, "toString"),
            "only the author's 'toString' should be in the class file",
        )
    }

    /** The descriptors of the methods of [className] named [name], in class-file order. */
    private fun methodDescriptors(
        program: TestSupport.LoadedProgram,
        name: String,
        className: String = "Neko",
    ): List<String> {
        val descriptors = mutableListOf<String>()
        val classFile = program.directory.resolve("com/github/kusa233/test/$className.class")
        val reader = ClassReader(java.nio.file.Files.readAllBytes(classFile))
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    methodName: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (methodName == name) descriptors += descriptor
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return descriptors
    }
}
