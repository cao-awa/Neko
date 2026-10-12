package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the semantic pass: name resolution, type checking and the built-in library.
 *
 * These assert on the *first* error rather than on a count wherever possible, so a test keeps its
 * meaning when an unrelated diagnostic is added elsewhere in the pipeline.
 */
class TypeCheckingTest {

    private fun firstError(source: String): String? =
        TestSupport.errors(TestSupport.compile(source)).firstOrNull()?.message

    private fun messageFor(source: String, fragment: String) {
        val messages = TestSupport.messages(TestSupport.compile(source))
        assertTrue(
            messages.any { it.contains(fragment) },
            "expected a message containing '$fragment', but got: $messages",
        )
    }

    @Test
    fun `println accepts int, string and bool`() {
        val program = TestSupport.load(
            TestSupport.program(
                "static fun main() {",
                "    println(1)",
                "    println(\"text\")",
                "    println(true)",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("1\ntext\ntrue\n", output)
    }

    @Test
    fun `println rejects a value it has no overload for`() {
        messageFor(
            TestSupport.program(
                "static fun main() {",
                "    println(1, 2)",
                "}",
            ),
            "println",
        )
    }

    @Test
    fun `a call to an unknown function is reported at the name`() {
        val source = TestSupport.program(
            "static fun main() {",
            "    missing()",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(error.message.contains("missing"), "unexpected message: ${error.message}")
        assertEquals(
            TestSupport.locationOf(source, "missing"),
            "${error.span?.line}:${error.span?.column}",
        )
    }

    @Test
    fun `an unknown type is reported where the type is written`() {
        val source = TestSupport.program(
            "static fun main() {",
            "    value: number = 1",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertEquals(
            TestSupport.locationOf(source, "number"),
            "${error.span?.line}:${error.span?.column}",
        )
        assertTrue(
            error.message.contains("number"),
            "the message should name the unknown type: ${error.message}",
        )
    }

    @Test
    fun `assigning a string to an int is rejected at the value`() {
        val source = TestSupport.program(
            "static fun main() {",
            "    value: int = 1",
            "    value = \"text\"",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("cannot assign") && error.message.contains("string"),
            "unexpected message: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "\"text\""),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the offending value",
        )
    }

    @Test
    fun `a declaration whose initializer has the wrong type is rejected`() {
        messageFor(
            TestSupport.program(
                "static fun main() {",
                "    value: int = \"text\"",
                "}",
            ),
            "string",
        )
    }

    @Test
    fun `an if condition must be bool`() {
        val source = TestSupport.program(
            "static fun main() {",
            "    if (1) {",
            "        println(\"no\")",
            "    }",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("must be bool"),
            "unexpected message: ${error.message}",
        )
    }

    @Test
    fun `a while condition must be bool`() {
        messageFor(
            TestSupport.program(
                "static fun main() {",
                "    while (\"text\") {",
                "        println(\"no\")",
                "    }",
                "}",
            ),
            "must be bool",
        )
    }

    @Test
    fun `arithmetic requires int operands`() {
        val source = TestSupport.program(
            "static fun main() {",
            "    value: int = 1 + \"text\"",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("int operands"),
            "unexpected message: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "\"text\""),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the operand that has the wrong type",
        )
    }

    @Test
    fun `calling a function with too few arguments is rejected`() {
        messageFor(
            TestSupport.program(
                "static fun add(a: int, b: int): int {",
                "    return a + b",
                "}",
                "static fun main() {",
                "    println(add(1))",
                "}",
            ),
            "argument",
        )
    }

    @Test
    fun `an argument of the wrong type is reported at that argument`() {
        val source = TestSupport.program(
            "static fun twice(value: int): int {",
            "    return value + value",
            "}",
            "static fun main() {",
            "    println(twice(\"text\"))",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertEquals(
            TestSupport.locationOf(source, "\"text\""),
            "${error.span?.line}:${error.span?.column}",
        )
    }

    @Test
    fun `returning the wrong type from a function is rejected`() {
        messageFor(
            TestSupport.program(
                "static fun value(): int {",
                "    return \"text\"",
                "}",
            ),
            "string",
        )
    }

    @Test
    fun `calling a lambda with the wrong arity is rejected`() {
        messageFor(
            TestSupport.program(
                "static fun main() {",
                "    f: [int, int] -> int = { a, b ->",
                "        yield a + b",
                "    }",
                "    println(f(1))",
                "}",
            ),
            "argument",
        )
    }

    @Test
    fun `a lambda must yield a value matching its declared return type`() {
        messageFor(
            TestSupport.program(
                "static fun main() {",
                "    f: [int] -> string = { a ->",
                "        yield a + 1",
                "    }",
                "}",
            ),
            "int",
        )
    }

    @Test
    fun `assignment to a captured variable inside a lambda is rejected`() {
        // Capture is by value, so this would silently not affect the enclosing variable.
        messageFor(
            TestSupport.program(
                "static fun main() {",
                "    counter: int = 0",
                "    bump: [] -> int = { ->",
                "        counter = counter + 1",
                "        yield counter",
                "    }",
                "}",
            ),
            "capture",
        )
    }

    @Test
    fun `a lambda may read a captured variable`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static fun main() {",
                "    base: int = 10",
                "    add: [int] -> int = { a ->",
                "        yield a + base",
                "    }",
                "}",
            ),
        )
        assertTrue(
            !result.hasErrors,
            "reading a captured variable is legal:\n" +
                TestSupport.errorLocations(result).joinToString("\n"),
        )
    }

    @Test
    fun `a local holding a function value can be called`() {
        // The callee here is a *value*, not a declaration, so code generation needs the callee's own
        // type to know which generated interface to dispatch through. Nothing else records it, so a
        // call written this way used to emit `println` against the lambda object and fail the
        // verifier — a wrong program that compiled cleanly.
        val program = TestSupport.load(
            TestSupport.program(
                "static fun main() {",
                "    read: [int] -> int = { extra ->",
                "        yield extra + 1",
                "    }",
                "    println(read(1))",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("2\n", output, "a call through a function value must dispatch to its interface")
    }

    @Test
    fun `a returned function value can be called`() {
        // The same rule one step further out: the callee is itself a call's result.
        val program = TestSupport.load(
            TestSupport.program(
                "static fun make(): [int] -> int {",
                "    return { extra ->",
                "        yield extra * 3",
                "    }",
                "}",
                "static fun main() {",
                "    println(make()(5))",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("15\n", output)
    }

    @Test
    fun `a field is visible from every function`() {
        val program = TestSupport.load(
            TestSupport.program(
                "static val answer: int = 42",
                "static fun first(): int {",
                "    return answer",
                "}",
                "static fun second(): int {",
                "    return answer + 1",
                "}",
                "static fun main() {",
                "    println(first())",
                "    println(second())",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Test", "main") }
        assertEquals("42\n43\n", output)
    }

    @Test
    fun `a field initializer may not refer to another field`() {
        // Every initializer runs in `<clinit>` in declaration order, so a forward reference would
        // read the field's default value rather than the value written later in the file.
        messageFor(
            TestSupport.program(
                "static val first: int = 1",
                "static val second: int = first + 1",
            ),
            "unresolved reference 'first'",
        )
    }

    @Test
    fun `a variable is visible only inside its block`() {
        messageFor(
            TestSupport.program(
                "static fun main() {",
                "    if (true) {",
                "        inner: int = 1",
                "    }",
                "    println(inner)",
                "}",
            ),
            "inner",
        )
    }

    @Test
    fun `a parameter may be reassigned`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static fun bump(value: int): int {",
                "    value = value + 1",
                "    return value",
                "}",
            ),
        )
        assertTrue(
            !result.hasErrors,
            "parameters are mutable:\n" +
                TestSupport.errorLocations(result).joinToString("\n"),
        )
    }

    @Test
    fun `an array parameter type produces a real JVM array descriptor`() {
        // Arrays exist as types — the sample's own `fun main(args: string[])` depends on it — even
        // though the language has no array literal or indexing syntax yet. The descriptor is the
        // proof that `string[]` became a genuine array type rather than a name that merely parsed.
        val program = TestSupport.load(
            TestSupport.program(
                "static fun count(values: int[], names: string[]) {",
                "}",
                "static fun main(args: string[]) {",
                "    println(\"entry point\")",
                "}",
            ),
        )
        val type = program.typeOf("com.github.kusa233.test.Test")
        assertEquals(
            "void",
            type.getMethod("count", IntArray::class.java, Array<String>::class.java).returnType.name,
        )
        val main = type.getMethod("main", Array<String>::class.java)
        assertEquals("void", main.returnType.name)
        assertEquals(
            listOf(Array<String>::class.java),
            main.parameterTypes.toList(),
            "'string[]' should become java.lang.String[]",
        )
    }

    @Test
    fun `an unresolved name inside a function is reported at that name`() {
        val source = TestSupport.program(
            "static fun main() {",
            "    println(unknown)",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertEquals(
            TestSupport.locationOf(source, "unknown"),
            "${error.span?.line}:${error.span?.column}",
        )
    }

    @Test
    fun `an unknown function name is distinguished from an unknown variable`() {
        // The two produce different advice, so the messages must differ: a call that resolves to
        // nothing is about the function, a bare name that resolves to nothing is about the name.
        val call = firstError(TestSupport.program("fun main() {", "    nope()", "}"))
        val name = firstError(TestSupport.program("fun main() {", "    println(nope)", "}"))
        assertEquals("there is no function named 'nope'", call)
        assertEquals("unresolved reference 'nope'", name)
    }

    @Test
    fun `a function may be declared before or after its use`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static fun main() {",
                "    println(later())",
                "}",
                "static fun later(): int {",
                "    return 7",
                "}",
            ),
        )
        assertTrue(
            !result.hasErrors,
            "declaration order should not matter:\n" +
                TestSupport.errorLocations(result).joinToString("\n"),
        )
    }

    @Test
    fun `a capitalized name used as a value is a construction`() {
        val program = TestSupport.load(
            TestSupport.program(
                "static fun main() {",
                "    value: CustomException = CustomException()",
                "}",
            ),
        )
        // Loading the class is enough: it proves `NEW`/`INVOKESPECIAL` targeted the right type.
        val bytes = program.result.classes.getValue("com/github/kusa233/test/Test")
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(
            text.contains("com/github/kusa233/test/CustomException"),
            "the generated bytecode should reference the constructed class",
        )
    }

    @Test
    fun `two errors in one function are both reported`() {
        val result = TestSupport.compile(
            TestSupport.program(
                "static fun main() {",
                "    println(unknown)",
                "    other = 1",
                "}",
            ),
        )
        assertEquals(
            2,
            TestSupport.errors(result).size,
            "independent problems should both be reported:\n" +
                TestSupport.errorLocations(result).joinToString("\n"),
        )
    }
}
