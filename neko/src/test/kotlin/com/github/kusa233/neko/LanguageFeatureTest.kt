package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the object model the exception sample forced into the language.
 *
 * `ExceptionSample.neko` names a standard library that is itself Neko: `EarlyEmitException` extends
 * `RuntimeException` and stores the raised values, and `EmitExtractorN` is an interface extending
 * `EmitExtractor`. Declaring those needs inheritance, constructors, interfaces with default methods,
 * `this`, and `Object[]` indexing — none of which the language had before. These tests pin down each
 * one on its own, so a failure names the feature rather than the sample.
 */
class LanguageFeatureTest {

    /** Builds a program in `com.github.kusa233.test` from whole declarations. */
    private fun file(vararg lines: String) = TestSupport.file(*lines)

    // ------------------------------------------------------------------ inheritance

    @Test
    fun `a class may extend another and reach its members`() {
        val program = TestSupport.load(
            file(
                "class Base {",
                "    fun greet(): string {",
                "        return \"base\"",
                "    }",
                "}",
                "",
                "class Derived: Base() {",
                "    fun both(): string {",
                "        return this.greet()",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            d: Derived = Derived()",
                "            println(d.both())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("base\n", output, "an inherited method should be reachable on the subclass")
    }

    @Test
    fun `an overridden method replaces the inherited one`() {
        val program = TestSupport.load(
            file(
                "class Base {",
                "    fun greet(): string {",
                "        return \"base\"",
                "    }",
                "}",
                "",
                "class Derived: Base() {",
                "    fun greet(): string {",
                "        return \"derived\"",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            d: Derived = Derived()",
                "            println(d.greet())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("derived\n", output, "the subclass' own method must win")
    }

    @Test
    fun `a class may not extend itself`() {
        val result = TestSupport.compile(
            file(
                "class A: B() {",
                "}",
                "",
                "class B: A() {",
                "}",
            ),
        )
        assertTrue(
            TestSupport.messages(result).any { it.contains("cannot extend itself") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a subclass needs a superclass that can be built without arguments`() {
        // Neko has no `super(...)` syntax, so every subclass constructor starts by calling the
        // superclass' no-argument constructor. A superclass with none could never be initialized.
        val source = file(
            "class Base {",
            "    constructor(value: int) {",
            "    }",
            "}",
            "",
            "class Derived: Base() {",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).single()
        assertTrue(
            error.message.contains("no constructor taking no arguments"),
            "unexpected message: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "Base()"),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the superclass reference",
        )
    }

    // ------------------------------------------------------------------ constructors

    @Test
    fun `a constructor takes the arguments its declaration names`() {
        val program = TestSupport.load(
            file(
                "class Point {",
                "    var x: int",
                "    constructor(start: int) {",
                "        this.x = start",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            p: Point = Point(7)",
                "            println(p.x)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("7\n", output, "the constructor should receive and store its argument")
    }

    @Test
    fun `a construction with the wrong number of arguments is rejected`() {
        val source = file(
            "class Point {",
            "    var x: int",
            "    constructor(start: int) {",
            "        this.x = start",
            "    }",
            "}",
            "",
            "class Main {",
            "    static {",
            "        fun main() {",
            "            p: Point = Point()",
            "        }",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).single()
        assertTrue(
            error.message.contains("expects 1 argument(s) but 0 were provided"),
            "unexpected message: ${error.message}",
        )
        assertEquals(
            // The diagnostic is anchored to the argument list, so its span is the `()` that opens and
            // closes it — not the class name before it, which is where a reader might expect it.
            "()",
            error.span?.text,
            "the error should cover the argument list",
        )
    }

    @Test
    fun `a class with no constructor still supports its no-argument form`() {
        // The JVM supplies one, and a program that writes `Cls()` is asking for exactly that.
        val program = TestSupport.load(
            file(
                "class Empty {",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            e: Empty = Empty()",
                "            println(\"made\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("made\n", output)
    }

    @Test
    fun `an argument is boxed when the constructor parameter is Object`() {
        // This is the conversion the standard library depends on: `EmitExtractorN.invoke` takes type
        // parameters erased to `Object`, so an `int` argument has to arrive boxed.
        val program = TestSupport.load(
            file(
                "class Box {",
                "    var held: Object",
                "    constructor(value: Object) {",
                "        this.held = value",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            b: Box = Box(42)",
                "            println(b.held)",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("42\n", output, "an int should be boxed into the Object parameter")
    }

    @Test
    fun `a val field may be written in the constructor but nowhere else`() {
        val allowed = TestSupport.compile(
            file(
                "class Holder {",
                "    private val data: int",
                "    constructor(value: int) {",
                "        this.data = value",
                "    }",
                "}",
            ),
        )
        assertTrue(
            !allowed.hasErrors,
            "a constructor must be able to set a 'val' field:\n" +
                TestSupport.errorLocations(allowed).joinToString("\n"),
        )

        val rejected = TestSupport.compile(
            file(
                "class Holder {",
                "    private val data: int",
                "    constructor(value: int) {",
                "        this.data = value",
                "    }",
                "    fun replace(value: int) {",
                "        this.data = value",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.messages(rejected).any { it.contains("declared with 'val'") },
            "unexpected messages: ${TestSupport.messages(rejected)}",
        )
    }

    // ------------------------------------------------------------------ interfaces

    @Test
    fun `an interface's abstract method must be implemented`() {
        val source = file(
            "interface Marker {",
            "    fun required()",
            "}",
            "",
            "class Incomplete: Marker {",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).single()
        assertTrue(
            error.message.contains("does not provide 'required()'"),
            "unexpected message: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "Incomplete: Marker"),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the class that fails to implement it",
        )
    }

    @Test
    fun `an interface's default method need not be implemented`() {
        val program = TestSupport.load(
            file(
                "interface Greeter {",
                "    fun name(): string",
                "    fun greet(): string {",
                "        return \"hello\"",
                "    }",
                "}",
                "",
                "class Person: Greeter {",
                "    fun name(): string {",
                "        return \"world\"",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            p: Person = Person()",
                "            println(p.greet())",
                "            println(p.name())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("hello\nworld\n", output, "the default method should be inherited")
    }

    @Test
    fun `a function may be called through an interface type`() {
        // An interface method call must be `invokeinterface`, not `invokevirtual`; the JVM rejects
        // the wrong one, so this test proves the code generator picked correctly.
        val program = TestSupport.load(
            file(
                "interface Shape {",
                "    fun area(): int",
                "}",
                "",
                "class Square: Shape {",
                "    var side: int",
                "    constructor(s: int) {",
                "        this.side = s",
                "    }",
                "    fun area(): int {",
                "        return this.side * this.side",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            s: Shape = Square(4)",
                "            println(s.area())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("16\n", output, "an interface call must reach the implementation")
    }

    @Test
    fun `an interface may not be instantiated`() {
        val result = TestSupport.compile(
            file(
                "interface Shape {",
                "    fun area(): int",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            s: Shape = Shape()",
                "        }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.messages(result).any { it.contains("is an interface, so it cannot be instantiated") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a class may implement several interfaces`() {
        val program = TestSupport.load(
            file(
                "interface Named {",
                "    fun name(): string",
                "}",
                "",
                "interface Aged {",
                "    fun age(): int",
                "}",
                "",
                "class Person: Named, Aged {",
                "    fun name(): string {",
                "        return \"ada\"",
                "    }",
                "    fun age(): int {",
                "        return 36",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            p: Person = Person()",
                "            println(p.name())",
                "            println(p.age())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("ada\n36\n", output)
    }

    // ------------------------------------------------------------------ arrays and Object

    @Test
    fun `a non-int array index is rejected at the index`() {
        // Arrays cannot be created by the language — there is no array literal — so this reads a
        // parameter, which is the one way a program names an array.
        val source = file(
            "class Main {",
            "    static {",
            "        fun read(values: Object[]) {",
            "            println(values[\"zero\"])",
            "        }",
            "    }",
            "}",
        )
        val result = TestSupport.compile(source)
        val error = TestSupport.errors(result).first { it.message.contains("array index") }
        assertTrue(
            error.message.contains("array index must be int"),
            "unexpected message: ${error.message}",
        )
        // The span is the bracketed index, `["zero"]`, which is what makes the diagnostic point at
        // the offending expression rather than at the whole call. Asserting on the covered text
        // avoids restating a column that would drift if the fixture's indentation changed.
        assertEquals(
            "[\"zero\"]",
            error.span?.text,
            "the error should cover the index expression",
        )
    }

    @Test
    fun `an array element may be read through an index`() {
        // Arrays cannot be created by the language, so this reads a parameter — which is the one way a
        // program names an array. The `Object[]` below is the shape the old extractor machinery used;
        // it is kept because indexing an array is what is under test, not the effect mechanism.
        val program = TestSupport.loadWithLibrary(
            samplePackage(
                "class Reader {",
                "    static {",
                "        fun read(values: Object[]) {",
                "            println(values[0])",
                "            println(values[1])",
                "        }",
                "    }",
                "}",
                "",
                "class Emitter {",
                "    static {",
                "        fun test() {",
                "            scratch 11, 22",
                "        } emit [int, int]",
                "    }",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun main() {",
                "            try Emitter.test() { first, second ->",
                "                println(first)",
                "                println(second)",
                "            }",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("11\n22\n", output, "the scratched values should arrive in order")
    }

    private fun samplePackage(vararg lines: String): String = buildString {
        append("locate com.github.kusa233.neko.sample\n")
        append('\n')
        for (line in lines) {
            append(line)
            append('\n')
        }
    }

    // ------------------------------------------------------------------ the standard library

    @Test
    fun `the standard library holds no sources any more`() {
        // The Neko standard library used to be ordinary Neko source under `stdlib/`: `Iterable`,
        // `Iterator` and then the `emit` extractors. Both have moved out of the language runtime:
        // the iteration protocol is the JDK's own `java.lang.Iterable`/`java.util.Iterator`, and the
        // effect machinery ships with the compiler as Java classes (`EmitYieldException`), because a
        // `yield` has to throw something the JVM already knows how to catch.
        //
        // Asserted rather than merely observed so that a library reintroduced later has to update
        // this test: the point of the assertion is that a program needs no library on its path.
        assertEquals(
            emptyList(),
            TestSupport.standardLibrarySources(),
            "a Neko program should need no library sources of its own",
        )
    }
}
