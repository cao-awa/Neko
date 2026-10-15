package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the eight primitive types.
 *
 * `int`, `bool`, `string`, `Object` and `Throwable` keep their own tests elsewhere; these cover what
 * completing the set to Java's eight added — the four new spellings, the literals that make them
 * usable, arithmetic and comparison within each of them, and the slot arithmetic the two-slot types
 * need.
 *
 * The runtime tests load and *run* the generated classes wherever the point is that a value survives
 * the lowering. A test that only asserted "it compiled" would pass on exactly the mistake this is
 * most likely to hide — a `long` local allocated one slot instead of two prints a wrong number rather
 * than failing to verify, and only running it shows that.
 */
class PrimitiveTypeTest {

    private fun messageFor(source: String, fragment: String) {
        val messages = TestSupport.messages(TestSupport.compile(source))
        assertTrue(
            messages.any { it.contains(fragment) },
            "expected a message containing '$fragment', but got: $messages",
        )
    }

    /** Compiles [source] as a program whose `main` prints, and returns what it printed. */
    private fun run(vararg bodyLines: String): String {
        val program = TestSupport.load(TestSupport.program(*bodyLines))
        return program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
    }

    // ------------------------------------------------------- declaring and printing

    @Test
    fun `every primitive type declares, assigns and prints`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        b: byte = 1",
            "        s: short = 2",
            "        c: char = 'c'",
            "        i: int = 3",
            "        l: long = 4",
            "        f: float = 5.5f",
            "        d: double = 6.5",
            "        z: bool = true",
            "        println(b)",
            "        println(s)",
            "        println(c)",
            "        println(i)",
            "        println(l)",
            "        println(f)",
            "        println(d)",
            "        println(z)",
            "    }",
            "}",
        )
        assertEquals("1\n2\nc\n3\n4\n5.5\n6.5\ntrue\n", output)
    }

    @Test
    fun `each new type is also spelled with its Java reference name`() {
        // The alias is the *Java* spelling of the same type, not a distinct boxed type: `Byte` and
        // `byte` have to be one type, or a `byte` value could not be stored in a `Byte` variable.
        val output = run(
            "static {",
            "    fun main() {",
            "        b: Byte = 1",
            "        s: Short = 2",
            "        c: Character = 'c'",
            "        i: Integer = 3",
            "        l: Long = 4",
            "        f: Float = 5.5f",
            "        d: Double = 6.5",
            "        z: Boolean = true",
            "        println(b)",
            "        println(s)",
            "        println(c)",
            "        println(i)",
            "        println(l)",
            "        println(f)",
            "        println(d)",
            "        println(z)",
            "    }",
            "}",
        )
        assertEquals("1\n2\nc\n3\n4\n5.5\n6.5\ntrue\n", output)
    }

    @Test
    fun `boolean is another spelling of bool`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        z: boolean = true",
            "        println(z)",
            "    }",
            "}",
        )
        assertEquals("true\n", output)
    }

    @Test
    fun `a primitive value may be held where an Object is expected`() {
        // Boxing is what `raise` and a type argument rely on, so it has to work for every new type
        // and not only for `int`: each one answers its own Java wrapper's `toString`.
        val output = run(
            "static {",
            "    fun show(v: Object) {",
            "        println(v)",
            "    }",
            "    fun main() {",
            "        b: byte = 1",
            "        s: short = 2",
            "        c: char = 'c'",
            "        l: long = 3",
            "        f: float = 4.5f",
            "        d: double = 5.5",
            "        show(b)",
            "        show(s)",
            "        show(c)",
            "        show(l)",
            "        show(f)",
            "        show(d)",
            "    }",
            "}",
        )
        assertEquals("1\n2\nc\n3\n4.5\n5.5\n", output)
    }

    // ------------------------------------------------------------------- literals

    @Test
    fun `long, float, double and char literals have the type they are written with`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        l: long = 1L",
            "        f: float = 1.5f",
            "        g: float = 1.5F",
            "        d: double = 1.5",
            "        c: char = 'a'",
            "        println(l)",
            "        println(f)",
            "        println(g)",
            "        println(d)",
            "        println(c)",
            "    }",
            "}",
        )
        assertEquals("1\n1.5\n1.5\n1.5\na\n", output)
    }

    @Test
    fun `an exponent is part of a decimal literal`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        small: double = 1.5e-3",
            "        big: double = 2e3",
            "        smallF: float = 2.5e-1f",
            "        println(small)",
            "        println(big)",
            "        println(smallF)",
            "    }",
            "}",
        )
        assertEquals("0.0015\n2000.0\n0.25\n", output)
    }

    @Test
    fun `a long literal holds a value beyond the int range`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        l: long = 4000000000L",
            "        println(l)",
            "    }",
            "}",
        )
        assertEquals("4000000000\n", output)
    }

    @Test
    fun `a whole-number literal takes the type its context asks for`() {
        // The one implicit conversion the language has, and it is confined to literals: the value is
        // written without a type, so the declaration gives it one.
        val output = run(
            "static {",
            "    fun main() {",
            "        b: byte = 1",
            "        s: short = 2",
            "        c: char = 3",
            "        i: int = 4",
            "        l: long = 5",
            "        println(b)",
            "        println(s)",
            "        println(c)",
            "        println(i)",
            "        println(l)",
            "    }",
            "}",
        )
        // `char` value 3 is the end-of-text control character, which is exactly what the code point
        // says; the point here is that the literal was accepted as a `char` at all.
        assertEquals("1\n2\n\u0003\n4\n5\n", output)
    }

    @Test
    fun `a literal in an arithmetic expression takes the type of the other side`() {
        // `1 + 2` where a `long` is expected is `long + long`, because both literals are read as
        // `long`. The mixed-type rule would otherwise reject its own context.
        val output = run(
            "static {",
            "    fun main() {",
            "        l: long = 1 + 2",
            "        println(l)",
            "    }",
            "}",
        )
        assertEquals("3\n", output)
    }

    @Test
    fun `a negative literal is checked against the low end of its type`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        b: byte = -128",
            "        s: short = -32768",
            "        println(b)",
            "        println(s)",
            "    }",
            "}",
        )
        assertEquals("-128\n-32768\n", output)
    }

    // ------------------------------------------------------------ ranges

    @Test
    fun `a literal too large for its type is reported where it is written`() {
        val source = TestSupport.program(
            "static {",
            "    fun main() {",
            "        value: byte = 300",
            "    }",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertEquals(
            TestSupport.locationOf(source, "300"),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the value that does not fit",
        )
        assertTrue(
            error.message.contains("does not fit") && error.message.contains("byte"),
            "unexpected message: ${error.message}",
        )
        assertTrue(
            error.message.contains("-128") && error.message.contains("127"),
            "the message should name the range the value has to be in: ${error.message}",
        )
    }

    @Test
    fun `a literal below the low end of its type is reported`() {
        messageFor(
            TestSupport.program(
                "static {",
                "    fun main() {",
                "        value: char = -1",
                "    }",
                "}",
            ),
            "does not fit",
        )
    }

    @Test
    fun `a long literal cannot be narrowed by its context`() {
        // The `L` names the 64-bit type outright, so `x: int = 1L` is a disagreement about types
        // rather than about a value — and the message says which one to drop.
        val source = TestSupport.program(
            "static {",
            "    fun main() {",
            "        value: int = 1L",
            "    }",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("long") && error.message.contains("int"),
            "unexpected message: ${error.message}",
        )
    }

    @Test
    fun `an integer literal wider than 64 bits is rejected by the lexer`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static {",
                "    fun main() {",
                "        value: long = 99999999999999999999",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.messages(result).any { it.contains("64-bit") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a decimal literal does not become the float it is assigned to`() {
        // `1.5` is a `double`, and a `float` is narrower: the suffix is what asks for the narrower
        // type, exactly as `L` does for a whole number.
        val source = TestSupport.program(
            "static {",
            "    fun main() {",
            "        value: float = 1.5",
            "    }",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("float") && error.message.contains("1.5f"),
            "the message should say to write the suffix: ${error.message}",
        )
    }

    @Test
    fun `a whole number does not become the double it is assigned to`() {
        val source = TestSupport.program(
            "static {",
            "    fun main() {",
            "        value: double = 1",
            "    }",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("1.0"),
            "the message should say to write the value as a double: ${error.message}",
        )
    }

    // ---------------------------------------------------------- strictness

    @Test
    fun `a value of one numeric type is not converted to another`() {
        // Java widens this `int` to a `long` silently. Neko does not: only a *literal* may be read as
        // another type, because only a literal has no type of its own yet.
        val source = TestSupport.program(
            "static {",
            "    fun main() {",
            "        small: int = 1",
            "        wide: long = small",
            "    }",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("declared as long") &&
                error.message.contains("type int"),
            "unexpected message: ${error.message}",
        )
    }

    @Test
    fun `mixing two numeric types in an operation is rejected`() {
        // Neither side is a literal, so no context can read one of them as the other's type: this is
        // the case Java silently widens.
        val source = TestSupport.program(
            "static {",
            "    fun main() {",
            "        wide: long = 1L",
            "        narrow: int = 2",
            "        total: long = wide + narrow",
            "    }",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertEquals(
            TestSupport.locationOf(source, "narrow", occurrence = 2),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the operand of the wrong type",
        )
        assertTrue(
            error.message.contains("same numeric type"),
            "unexpected message: ${error.message}",
        )
    }

    @Test
    fun `a literal beside a value of another type still takes that type`() {
        // The other half of the rule: `2` has no type of its own, so it is read as the `long` its
        // neighbour is — which is what makes `wide + 2` work where `wide + narrow` does not.
        val output = run(
            "static {",
            "    fun main() {",
            "        wide: long = 1L",
            "        println(wide + 2)",
            "    }",
            "}",
        )
        assertEquals("3\n", output)
    }

    @Test
    fun `mixing a long with a double is rejected`() {
        messageFor(
            TestSupport.program(
                "static {",
                "    fun main() {",
                "        first: long = 1L",
                "        second: double = 1.0",
                "        total: double = first + second",
                "    }",
                "}",
            ),
            "same numeric type",
        )
    }

    @Test
    fun `adding a string to a long is still an error`() {
        messageFor(
            TestSupport.program(
                "static {",
                "    fun main() {",
                "        value: long = 1L + \"text\"",
                "    }",
                "}",
            ),
            "int operands",
        )
    }

    @Test
    fun `a bool is not a number`() {
        messageFor(
            TestSupport.program(
                "static {",
                "    fun main() {",
                "        value: bool = true + false",
                "    }",
                "}",
            ),
            "int operands",
        )
    }

    @Test
    fun `negating a non-numeric value is rejected`() {
        messageFor(
            TestSupport.program(
                "static {",
                "    fun main() {",
                "        value: string = -\"text\"",
                "    }",
                "}",
            ),
            "numeric operand",
        )
    }

    // -------------------------------------------------------- arithmetic and comparison

    @Test
    fun `arithmetic stays within the type of its operands`() {
        val output = run(
            "static {",
            "    fun bytes(): byte {",
            "        a: byte = 10",
            "        b: byte = 3",
            "        return a + b",
            "    }",
            "    fun shorts(): short {",
            "        a: short = 10",
            "        b: short = 3",
            "        return a - b",
            "    }",
            "    fun chars(): char {",
            "        a: char = 'a'",
            "        b: char = 'b'",
            "        return a + b",
            "    }",
            "    fun ints(): int {",
            "        a: int = 10",
            "        b: int = 3",
            "        return a * b",
            "    }",
            "    fun longs(): long {",
            "        a: long = 10L",
            "        b: long = 3L",
            "        return a / b",
            "    }",
            "    fun floats(): float {",
            "        a: float = 10.5f",
            "        b: float = 3.0f",
            "        return a % b",
            "    }",
            "    fun doubles(): double {",
            "        a: double = 10.5",
            "        b: double = 3.0",
            "        return a + b",
            "    }",
            "    fun main() {",
            "        println(bytes())",
            "        println(shorts())",
            "        println(chars())",
            "        println(ints())",
            "        println(longs())",
            "        println(floats())",
            "        println(doubles())",
            "    }",
            "}",
        )
        // 'a' is 97 and 'b' is 98, and a `char` sum is a `char` sum: 195 is 'Ã'.
        assertEquals("13\n7\n\u00C3\n30\n3\n1.5\n13.5\n", output)
    }

    @Test
    fun `byte arithmetic wraps at the width of the type`() {
        // `byte` is an `int` to the JVM, so `100 + 100` computes 200 and has to be narrowed back on
        // the way into the local. Printing 200 for a type whose range ends at 127 would mean the
        // declared type was a decoration.
        val output = run(
            "static {",
            "    fun main() {",
            "        a: byte = 100",
            "        b: byte = 100",
            "        sum: byte = a + b",
            "        println(sum)",
            "    }",
            "}",
        )
        assertEquals("-56\n", output)
    }

    @Test
    fun `every numeric type compares with the right opcode`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        b: byte = 1",
            "        s: short = 2",
            "        c: char = 'c'",
            "        i: int = 3",
            "        l: long = 4L",
            "        f: float = 5.5f",
            "        d: double = 6.5",
            "        println(b < 2)",
            "        println(s <= 2)",
            "        println(c > 'b')",
            "        println(i >= 3)",
            "        println(l < 5L)",
            "        println(l > 3L)",
            "        println(f <= 5.5f)",
            "        println(f >= 5.5f)",
            "        println(d > 6.0)",
            "        println(d < 7.0)",
            "        println(4000000000L > 1L)",
            "    }",
            "}",
        )
        assertEquals("true\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\n", output)
    }

    @Test
    fun `a comparison against NaN answers false, as IEEE 754 says`() {
        // The whole reason `cmpl` and `cmpg` both exist. `NaN` is neither less nor greater than
        // anything, so every relational operator has to answer false — including `>=`, which a
        // `cmpg`-only implementation would get wrong.
        val output = run(
            "static {",
            "    fun nan(): double {",
            "        zero: double = 0.0",
            "        return zero / zero",
            "    }",
            "    fun main() {",
            "        n: double = nan()",
            "        one: double = 1.0",
            "        println(n < one)",
            "        println(n <= one)",
            "        println(n > one)",
            "        println(n >= one)",
            "        println(n == n)",
            "    }",
            "}",
        )
        assertEquals("false\nfalse\nfalse\nfalse\nfalse\n", output)
    }

    @Test
    fun `every numeric type compares with equality`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        b: byte = 1",
            "        s: short = 2",
            "        c: char = 'c'",
            "        i: int = 3",
            "        l: long = 4L",
            "        f: float = 5.5f",
            "        d: double = 6.5",
            "        println(b == 1)",
            "        println(s == 2)",
            "        println(c == 'c')",
            "        println(i == 3)",
            "        println(l == 4L)",
            "        println(f == 5.5f)",
            "        println(d == 6.5)",
            "        println(b != 2)",
            "        println(l != 5L)",
            "        println(f != 6.0f)",
            "        println(d != 6.5)",
            "    }",
            "}",
        )
        assertEquals("true\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\nfalse\n", output)
    }

    @Test
    fun `a comparison chain works across a long operand`() {
        val output = run(
            "static {",
            "    fun main() {",
            "        a: long = 1L",
            "        b: long = 2L",
            "        c: long = 3L",
            "        println(a < b < c)",
            "        println(c > b > a)",
            "        println(a < c < b)",
            "    }",
            "}",
        )
        assertEquals("true\ntrue\nfalse\n", output)
    }

    @Test
    fun `a comparison chain of a narrow type reads its literals that way`() {
        // Every operand after the first is read as the one before it, so a chain over a `byte` is a
        // chain of `byte` comparisons rather than of `int` literals beside a `byte`.
        val output = run(
            "static {",
            "    fun main() {",
            "        b: byte = 1",
            "        println(b < 2 < 3)",
            "        println(b < 2 == false)",
            "    }",
            "}",
        )
        assertEquals("true\nfalse\n", output)
    }

    @Test
    fun `a when over a long subject matches and compares once`() {
        val output = run(
            "static {",
            "    var calls: int = 0",
            "    fun subject(): long {",
            "        calls = calls + 1",
            "        return 4000000000L",
            "    }",
            "    fun main() {",
            "        when (subject()) {",
            "            1L -> println(\"one\")",
            "            4000000000L -> println(\"big\")",
            "            else -> println(\"other\")",
            "        }",
            "        println(calls)",
            "    }",
            "}",
        )
        assertEquals("big\n1\n", output)
    }

    // ------------------------------------------------------------------ arrays

    @Test
    fun `an array of every new type is declared with its own descriptor`() {
        // Neko has no array literal, so an array can only ever arrive as a parameter and this is the
        // only place a test can observe one. The descriptor is what the element type decides — `[B`
        // for a `byte[]`, `[J` for a `long[]` — and it is also what picks the `*aload`/`*astore` pair
        // both ends of an element access use, so a wrong entry here is a wrong opcode everywhere.
        val program = TestSupport.load(
            TestSupport.program(
                "static {",
                "    fun bytes(a: byte[]) {",
                "        a[0] = 120",
                "    }",
                "    fun shorts(a: short[]) {",
                "        a[0] = 30000",
                "    }",
                "    fun chars(a: char[]) {",
                "        a[0] = 'z'",
                "    }",
                "    fun ints(a: int[]) {",
                "        a[0] = 7",
                "    }",
                "    fun longs(a: long[]) {",
                "        a[0] = 4000000000L",
                "    }",
                "    fun floats(a: float[]) {",
                "        a[0] = 1.5f",
                "    }",
                "    fun doubles(a: double[]) {",
                "        a[0] = 2.5",
                "    }",
                "    fun bools(a: bool[]) {",
                "        a[0] = true",
                "    }",
                "    fun reads(a: byte[]): byte {",
                "        return a[0]",
                "    }",
                "}",
            ),
        )
        val type = program.typeOf("com.github.kusa233.test.Test")
        assertArrayParameter(type, "bytes", java.lang.Byte.TYPE)
        assertArrayParameter(type, "shorts", java.lang.Short.TYPE)
        assertArrayParameter(type, "chars", java.lang.Character.TYPE)
        assertArrayParameter(type, "ints", java.lang.Integer.TYPE)
        assertArrayParameter(type, "longs", java.lang.Long.TYPE)
        assertArrayParameter(type, "floats", java.lang.Float.TYPE)
        assertArrayParameter(type, "doubles", java.lang.Double.TYPE)
        assertArrayParameter(type, "bools", java.lang.Boolean.TYPE)
        assertEquals(
            java.lang.Byte.TYPE,
            type.getMethod("reads", ByteArray::class.java).returnType,
            "reading one element of a byte[] must answer a byte",
        )
    }

    /**
     * Asserts that [name] takes exactly one array parameter whose element type is [element].
     *
     * The element type is read off the generated class rather than asserted by name, because it is
     * what the JVM puts in the descriptor — `[B` for a `byte[]`, `[J` for a `long[]` — and it is also
     * what picks the `*aload`/`*astore` pair both ends of an element access use.
     */
    private fun assertArrayParameter(type: Class<*>, name: String, element: Class<*>) {
        val method = type.methods.filter { it.name == name }.single()
        val parameter = method.parameterTypes.single()
        assertTrue(parameter.isArray, "'$name' should take an array")
        assertEquals(element, parameter.componentType, "'$name' should take an array of $element")
    }

    @Test
    fun `an emit clause carries wide values through its builder`() {
        // A `raise` is the one construct that carries several values of declared types through an
        // `Object[]`, and the builder it generates is where a two-slot parameter would be read from
        // the wrong slot if `long` were treated as one: the `long` is boxed from slots 0 and 1, and
        // the `int` after it is read from slot 2.
        val program = TestSupport.loadWithLibrary(
            TestSupport.file(
                "class Test {",
                "    static {",
                "        fun give() {",
                "            raise 4000000000L, 7, 'q', 2.5",
                "        } emit [long, int, char, double]",
                "",
                "        fun main() {",
                "            try give() { w, i, c, d ->",
                "                println(w)",
                "                println(i)",
                "                println(c)",
                "                println(d)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Test", "main")
        }
        assertEquals("4000000000\n7\nq\n2.5\n", output)
    }

    // ------------------------------------------------------- two-slot locals

    @Test
    fun `an int declared after a long local holds its own value`() {
        // The trap: a `long` occupies two JVM local slots, so an allocator that hands out one leaves
        // the next local overlapping its upper half. The class can still verify — the overlapping
        // slot is typed `int` either way — and the value is simply wrong, which is why this test runs
        // the program instead of only compiling it.
        val output = run(
            "static {",
            "    fun compute(): int {",
            "        wide: long = 4000000000L",
            "        narrow: int = 12345",
            "        println(wide)",
            "        return narrow",
            "    }",
            "    fun main() {",
            "        println(compute())",
            "    }",
            "}",
        )
        assertEquals("4000000000\n12345\n", output)
    }

    @Test
    fun `a long local declared after another long local holds its own value`() {
        val output = run(
            "static {",
            "    fun compute(): long {",
            "        first: long = 111111111111L",
            "        second: long = 222222222222L",
            "        third: int = 7",
            "        println(first)",
            "        println(third)",
            "        return second",
            "    }",
            "    fun main() {",
            "        println(compute())",
            "    }",
            "}",
        )
        assertEquals("111111111111\n7\n222222222222\n", output)
    }

    @Test
    fun `a double local and an int local occupy their own slots`() {
        val output = run(
            "static {",
            "    fun compute(): double {",
            "        wide: double = 1.5",
            "        narrow: int = 42",
            "        other: double = 2.5",
            "        println(narrow)",
            "        return wide + other",
            "    }",
            "    fun main() {",
            "        println(compute())",
            "    }",
            "}",
        )
        assertEquals("42\n4.0\n", output)
    }

    @Test
    fun `a long parameter and a local after it hold their own values`() {
        // The parameter slots are allocated by the same rule, so a `long` parameter pushes whatever
        // follows it along by one — including the first local of the body, which is an `int` here and
        // so would sit inside the `long` if the parameter had been given one slot.
        val output = run(
            "static {",
            "    fun combine(wide: long, narrow: int): long {",
            "        extra: int = narrow",
            "        println(extra)",
            "        println(narrow)",
            "        return wide",
            "    }",
            "    fun main() {",
            "        println(combine(4000000000L, 41))",
            "    }",
            "}",
        )
        assertEquals("41\n41\n4000000000\n", output)
    }

    @Test
    fun `a long temporary does not overlap the next temporary`() {
        // `when` and a comparison chain both keep values in compiler-generated temporaries, which go
        // through the same allocator as declared locals.
        val output = run(
            "static {",
            "    fun choose(a: long, b: long): long {",
            "        when (a) {",
            "            4000000000L -> return b",
            "            else -> return a",
            "        }",
            "    }",
            "    fun main() {",
            "        println(choose(4000000000L, 5L))",
            "        println(choose(1L, 5L))",
            "        println(1L < 4000000000L < 5000000000L)",
            "    }",
            "}",
        )
        assertEquals("5\n1\ntrue\n", output)
    }

    @Test
    fun `a long field holds its own value`() {
        val output = run(
            "static {",
            "    static var counter: long = 4000000000L",
            "    fun bump(): long {",
            "        counter = counter + 1L",
            "        return counter",
            "    }",
            "    fun main() {",
            "        println(bump())",
            "        println(bump())",
            "    }",
            "}",
        )
        assertEquals("4000000001\n4000000002\n", output)
    }

    @Test
    fun `wide fields are compared and hashed by the derived methods`() {
        // A `meow class` derives `equals`/`hashCode` from its fields. A `long` field cannot be compared
        // with a branch instruction or hashed as its first four bytes, so a derived method that treated
        // it like an `int` would answer the wrong thing while still compiling.
        val program = TestSupport.load(
            TestSupport.file(
                "meow class Wide(val big: long, val small: int) {",
                "}",
                "",
                "class Test {",
                "    static {",
                "        fun main() {",
                "            a: Wide = Wide(4000000000L, 1)",
                "            b: Wide = Wide(4000000000L, 1)",
                "            c: Wide = Wide(4000000001L, 1)",
                "            println(a == b)",
                "            println(a == c)",
                "            println(a.hashCode() == b.hashCode())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Test", "main")
        }
        assertEquals("true\nfalse\ntrue\n", output)
    }
}
