package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for `static`, instance members, and `obj.member`.
 *
 * The rule under test is that a member without `static` belongs to an *object*: it is reachable only
 * through one, and the object must be created first. That is why a bare reference to an instance
 * member from a static context is an error rather than a compile-time convenience — the language has
 * no `this` to offer there, and inventing one would hide the object the author never made.
 *
 * Every program below declares its own classes, because the rule is about how a *class* is written.
 * A top level holds class declarations and nothing else, so the `main` entry point is a static member
 * of one of them — which is exactly the shape the rule requires.
 */
class InstanceMemberTest {

    /** A program with a `Counter` class and a static `main` that may use a `c: Counter`. */
    private fun withCounter(vararg mainLines: String): String = TestSupport.file(
        "class Counter {",
        "    var count: int = 0",
        "    fun bump(): int {",
        "        count = count + 1",
        "        return count",
        "    }",
        "}",
        "",
        "class Main {",
        "    static fun main() {",
        "        c: Counter = Counter()",
        *mainLines,
        "    }",
        "}",
    )

    @Test
    fun `a static member is reachable without an object`() {
        val program = TestSupport.load(
            TestSupport.file(
                "class Config {",
                "    static val answer: int = 42",
                "    static fun twice(value: int): int {",
                "        return value + value",
                "    }",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        println(Config.answer)",
                "        println(Config.twice(21))",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("42\n42\n", output)
    }

    @Test
    fun `an instance member is reachable through an object`() {
        // The whole point of the instance model: `Point()` creates the object, and only then do its
        // field and function become reachable.
        val program = TestSupport.load(
            TestSupport.file(
                "class Point {",
                "    var x: int = 5",
                "    fun get(): int {",
                "        return x",
                "    }",
                "    fun bump() {",
                "        x = x + 1",
                "    }",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        p: Point = Point()",
                "        println(p.x)",
                "        p.bump()",
                "        println(p.get())",
                "        println(p.x)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("5\n6\n6\n", output, "member access should read and write the same object")
    }

    @Test
    fun `an instance field is not reachable from a static context`() {
        val source = TestSupport.file(
            "class Counter {",
            "    val start: int = 5",
            "    static fun bad() {",
            "        println(start)",
            "    }",
            "}",
        )
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("create an object first"),
            "the error should say how to fix it, but was: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "start", occurrence = 2),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the offending name",
        )
    }

    @Test
    fun `an instance function is not callable from a static context`() {
        val result = TestSupport.compile(
            TestSupport.file(
                "class Counter {",
                "    fun bump() {",
                "    }",
                "    static fun bad() {",
                "        bump()",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("create an object first") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `an instance member is reachable from another instance member without naming an object`() {
        // Inside a non-static member there *is* a receiver — the object the call arrived on — so the
        // bare name resolves to it rather than being rejected.
        val program = TestSupport.load(
            TestSupport.file(
                "class Counter {",
                "    var count: int = 0",
                "    fun bump() {",
                "        count = count + 1",
                "    }",
                "    fun twice() {",
                "        bump()",
                "        bump()",
                "    }",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        c: Counter = Counter()",
                "        c.twice()",
                "        println(c.count)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        // `twice()` calls `bump()` twice without naming an object; both calls must land on the same
        // object, so the count is 2. If implicit `this` were dropped or a fresh object made, it would
        // be 1.
        assertEquals("2\n", output, "implicit 'this' should reach the same object")
    }

    @Test
    fun `an instance field initializer runs per object`() {
        // A non-static field's initializer belongs in `<init>`, not `<clinit>`: each object gets its
        // own value rather than sharing one across the class.
        val program = TestSupport.load(
            TestSupport.file(
                "class Counter {",
                "    var count: int = 0",
                "    fun bump() {",
                "        count = count + 1",
                "    }",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        a: Counter = Counter()",
                "        b: Counter = Counter()",
                "        a.bump()",
                "        a.bump()",
                "        b.bump()",
                "        println(a.count)",
                "        println(b.count)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("2\n1\n", output, "each object must have its own copy of the field")
    }

    @Test
    fun `a static function is not reachable through an object`() {
        // The rule cuts both ways: a static member belongs to the class, so reaching it through an
        // instance is rejected and the message names the spelling that works.
        val result = TestSupport.compile(
            TestSupport.file(
                "class Counter {",
                "    static fun total(): int {",
                "        return 0",
                "    }",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        c: Counter = Counter()",
                "        println(c.total())",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("write 'Counter.total(...)'") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a static member is reachable by class name`() {
        val program = TestSupport.load(
            TestSupport.file(
                "class Config {",
                "    static val limit: int = 9",
                "    static fun total(): int {",
                "        return 3",
                "    }",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        println(Config.limit)",
                "        println(Config.total())",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("9\n3\n", output)
    }

    @Test
    fun `an instance member is not reachable by class name`() {
        val result = TestSupport.compile(
            TestSupport.file(
                "class Counter {",
                "    val start: int = 5",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        println(Counter.start)",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("create an object first") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `accessing a member that does not exist names the class and the member`() {
        val source = withCounter("        println(c.missing)")
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertEquals("Counter has no member named 'missing'", error.message)
        assertEquals(
            TestSupport.locationOf(source, "missing"),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the member name",
        )
    }

    @Test
    fun `a function named without a call says how to call it`() {
        val result = TestSupport.compile(withCounter("        println(c.bump)"))
        assertTrue(
            TestSupport.messages(result).any { it.contains("to call it") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a member call with the wrong arity is reported at the argument list`() {
        val source = withCounter("        println(c.bump(1))")
        val error = TestSupport.errors(TestSupport.compile(source)).single()
        assertTrue(
            error.message.contains("expects 0 argument(s) but 1 was provided"),
            "unexpected message: ${error.message}",
        )
        assertEquals(
            TestSupport.locationOf(source, "(1)"),
            "${error.span?.line}:${error.span?.column}",
            "the error should point at the argument list",
        )
    }

    @Test
    fun `an object is reachable from a field of another object`() {
        val program = TestSupport.load(
            TestSupport.file(
                "class Inner {",
                "    val value: int = 7",
                "}",
                "class Outer {",
                "    val inner: Inner = Inner()",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        o: Outer = Outer()",
                "        println(o.inner.value)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("7\n", output, "a chain of member accesses should read through each object")
    }

    @Test
    fun `an explicit member assignment writes the object's field`() {
        val program = TestSupport.load(
            TestSupport.file(
                "class Point {",
                "    var x: int = 1",
                "    static var limit: int = 100",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        p: Point = Point()",
                "        p.x = 42",
                "        println(p.x)",
                "        Point.limit = 7",
                "        println(Point.limit)",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals("42\n7\n", output)
    }

    @Test
    fun `assigning an instance field by class name is rejected`() {
        val result = TestSupport.compile(
            TestSupport.file(
                "class Counter {",
                "    var start: int = 5",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        Counter.start = 1",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("create an object first") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `assigning a static field through an object is rejected`() {
        val result = TestSupport.compile(
            TestSupport.file(
                "class Counter {",
                "    static var total: int = 5",
                "}",
                "",
                "class Main {",
                "    static fun main() {",
                "        c: Counter = Counter()",
                "        c.total = 1",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("write 'Counter.total = ...'") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a class header may carry an empty parameter list`() {
        // `class ExceptionSample()` — the sample writes the parentheses even though the language has
        // no constructor parameters yet, so they must be accepted and must not change the class.
        val result = TestSupport.compile(
            TestSupport.file(
                "class Empty() {",
                "    static fun main() {",
                "    }",
                "}",
            ),
        )
        assertTrue(
            !result.hasErrors,
            "an empty class header parameter list is legal:\n" +
                result.diagnostics.joinToString("\n"),
        )
    }

    @Test
    fun `a class header parameter list must be empty`() {
        val result = TestSupport.compile(
            TestSupport.file(
                "class Bad(value: int) {",
                "    static fun main() {",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("constructor parameter") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }
}
