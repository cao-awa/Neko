package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for `miao class` — an enum whose constants the compiler creates.
 *
 * What is worth testing is that the constants are real `Enum` instances rather than, say, plain
 * static fields: `name()`, `ordinal()` and `valueOf` all come from `java.lang.Enum` and only work if
 * the class file is a genuine enum. So the assertions below are mostly about what a *run* does, with
 * one that reads the class file for the flags that make it one.
 */
class EnumTest {

    private fun file(vararg lines: String) = TestSupport.file(*lines)

    @Test
    fun `an enum keeps its constants in declaration order`() {
        val program = TestSupport.load(
            file(
                "miao class Color {",
                "    RED,",
                "    GREEN,",
                "    BLUE",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Color.RED.name())",
                "            println(Color.RED.ordinal())",
                "            println(Color.GREEN.ordinal())",
                "            println(Color.BLUE.ordinal())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "RED\n0\n1\n2\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
            "the ordinal is the position in the declaration",
        )
    }

    @Test
    fun `printing a constant shows its name`() {
        // This works because the constant is a real `java.lang.Enum`, whose `toString` answers the
        // name. A plain static field of a plain class would print something object-ish instead.
        val program = TestSupport.load(
            file(
                "miao class Color { RED, GREEN }",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Color.RED)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "RED\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `values answers every constant in order`() {
        val program = TestSupport.load(
            file(
                "miao class Color { RED, GREEN, BLUE }",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            all: Color[] = Color.values()",
                "            println(all[0])",
                "            println(all[1])",
                "            println(all[2])",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "RED\nGREEN\nBLUE\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `valueOf finds a constant by name`() {
        val program = TestSupport.load(
            file(
                "miao class Color { RED, GREEN }",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Color.valueOf(\"GREEN\"))",
                "            println(Color.valueOf(\"GREEN\") == Color.GREEN)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "GREEN\ntrue\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
            "valueOf answers the same instance the constant names",
        )
    }

    @Test
    fun `a constant can carry values declared in the header`() {
        // The header's fields become constructor parameters, exactly as a `meow class`' is, so each
        // constant supplies its own. The field is read through the instance like any other.
        val program = TestSupport.load(
            file(
                "miao class Code(val status: int) {",
                "    OK(200),",
                "    GONE(410)",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Code.OK.status)",
                "            println(Code.GONE.status)",
                "            println(Code.GONE.name())",
                "            println(Code.GONE.ordinal())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "200\n410\nGONE\n1\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `constants compare by identity`() {
        // An enum's `==` is reference identity: there is exactly one instance of each constant, which
        // is the whole reason an enum is usable as a set of tags.
        val program = TestSupport.load(
            file(
                "miao class Color { RED, GREEN }",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            a: Color = Color.RED",
                "            b: Color = Color.valueOf(\"RED\")",
                "            println(a == b)",
                "            println(Color.RED == Color.GREEN)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "true\nfalse\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `the class file is marked as an enum`() {
        // `ACC_ENUM` on the class and on each constant is what makes reflection — and any Java caller —
        // see an enum rather than a class that happens to hold static fields. It cannot be observed by
        // running the program, so the class file is read.
        val program = TestSupport.load(
            file(
                "miao class Color { RED, GREEN }",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(\"ok\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val type = program.typeOf("com.github.kusa233.test.Color")
        assertTrue(
            type.modifiers and ACC_ENUM != 0,
            "the generated class should be a real enum, got modifiers ${type.modifiers}",
        )
        for (name in listOf("RED", "GREEN")) {
            val field = type.getDeclaredField(name)
            assertTrue(
                field.modifiers and ACC_ENUM != 0,
                "constant $name should carry ACC_ENUM, got modifiers ${field.modifiers}",
            )
            assertTrue(
                java.lang.reflect.Modifier.isStatic(field.modifiers),
                "constant $name should be static",
            )
            assertTrue(
                java.lang.reflect.Modifier.isFinal(field.modifiers),
                "constant $name should be final",
            )
        }
    }

    @Test
    fun `an enum cannot override a method java dot lang Enum declares final`() {
        // The JVM refuses to load a class that overrides a final method, so without this the program
        // would compile and then die with a VerifyError. A message at the declaration is the whole
        // point of catching it in the analyzer.
        val result = TestSupport.compile(
            file(
                "miao class Color {",
                "    RED",
                "",
                "    fun name(): string {",
                "        return \"nope\"",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() { }",
            "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("declares it final") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `an enum may still override toString`() {
        // `Enum` leaves `toString` open, unlike `name`, so this has to be allowed — and it has to be
        // the enum's own body that runs.
        val program = TestSupport.load(
            file(
                "miao class Color {",
                "    RED",
                "",
                "    fun toString(): string {",
                "        return \"a colour\"",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Color.RED)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "a colour\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `a constant may be built from a static field of the same enum`() {
        // The `<clinit>` runs the reader's static initializers before it builds the constants, so an
        // argument may read one. This is the ordering an enum needs to be usable for anything table
        // shaped.
        val program = TestSupport.load(
            file(
                "miao class Code(val status: int) {",
                "    OK(MIN),",
                "    GONE(410)",
                "",
                "    static {",
                "        val MIN: int = 200",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            println(Code.OK.status)",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "200\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `values answers a copy so a caller cannot write into the enum`() {
        // Handing out `$VALUES` itself would let a caller overwrite a constant in the enum's own
        // array. The clone is what makes `values()` safe to hand to anyone.
        val program = TestSupport.load(
            file(
                "miao class Color { RED, GREEN }",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            first: Color[] = Color.values()",
                "            first[0] = Color.GREEN",
                "            second: Color[] = Color.values()",
                "            println(second[0])",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "RED\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
            "writing into one answer must not change the next",
        )
    }

    private companion object {
        /**
         * `ACC_ENUM`, which `java.lang.reflect.Modifier` has no helper for.
         *
         * The flag is public in the JVM's own specification but was only ever exposed through ASM and
         * the class-file constant, never through `Modifier` — so the bit is written out here, named.
         */
        const val ACC_ENUM = 0x4000
    }

    @Test
    fun `an enum with no constants is refused`() {
        val result = TestSupport.compile(
            file(
                "miao class Color { }",
                "",
                "class Main {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("must name at least one constant") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a duplicate constant is refused`() {
        val result = TestSupport.compile(
            file(
                "miao class Color { RED, RED }",
                "",
                "class Main {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("already declared") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `an enum cannot declare a constructor`() {
        val result = TestSupport.compile(
            file(
                "miao class Color(val code: int) {",
                "    RED(1)",
                "",
                "    constructor(code: int) {",
                "        this.code = code",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("cannot declare a constructor") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `an enum cannot extend another class`() {
        val result = TestSupport.compile(
            file(
                "class Base() {",
                "    static { }",
                "}",
                "",
                "miao class Color: Base() { RED }",
                "",
                "class Main {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("cannot extend") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a constant given the wrong number of arguments is refused`() {
        val result = TestSupport.compile(
            file(
                "miao class Code(val status: int) { OK }",
                "",
                "class Main {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("takes 1 argument but was given 0") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a constant given an argument of the wrong type is refused`() {
        val result = TestSupport.compile(
            file(
                "miao class Code(val status: int) { OK(\"no\") }",
                "",
                "class Main {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("but 'int' was expected") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }
}
