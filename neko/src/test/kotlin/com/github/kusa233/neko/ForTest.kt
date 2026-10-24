package com.github.kusa233.neko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for `for`, `for ... in`, and the `++`/`--` statements.
 *
 * `while` is not tested here: it already *is* Java's `while (condition) statement`, and `WhileTest`
 * covers it.
 *
 * Most of these run the compiled program, because a loop that runs the wrong number of times or
 * increments the wrong slot still compiles — only the answer tells a correct loop from a broken one.
 * The rest check the diagnostics, because the remaining mistakes are ones the language refuses: a
 * condition that is not a `bool`, an update that is not an increment, an assignment to a loop
 * variable.
 */
class ForTest {

    /**
     * Wraps [body] in the `Main` class a Neko file needs.
     *
     * Only class and interface declarations may sit at the top level of a file, so `main` is a `static`
     * member of a class rather than a top-level function — every test's program has to say so.
     */
    /**
     * Wraps [body] in the `Main` class a Neko file needs, with two arrays already in scope: `ns` is an
     * `int[]` and `xs` is a `string[]`.
     *
     * Only class and interface declarations may sit at the top level of a file, so `main` is a `static`
     * member of a class rather than a top-level function. The arrays arrive as *parameters* rather than
     * being built in Neko code because the language has neither an array literal nor array-creation
     * syntax — the only way a program gets an array is for its caller to pass one.
     */
    private fun program(vararg body: String): String = TestSupport.file(
        "class Main() {",
        "    static {",
        "        fun main(ns: int[], xs: string[], ls: long[]) {",
        *body.map { "            $it" }.toTypedArray(),
        "        }",
        "    }",
        "}",
    )

    /** Runs `main` with the given `int[]`, `string[]` and `long[]`, returning what it printed. */
    private fun run(
        numbers: IntArray,
        names: Array<String>,
        longs: LongArray,
        vararg body: String,
    ): List<String> {
        val program = TestSupport.loadWithLibrary(program(*body))
        assertEquals(emptyList(), TestSupport.messages(program.result))
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main", numbers, names, longs)
        }
        return output.lines().filter { it.isNotEmpty() }
    }

    /** Runs a program that reads none of the arrays. */
    private fun run(vararg body: String): List<String> =
        run(IntArray(0), emptyArray(), LongArray(0), *body)

    /** Compiles without running, for the cases whose point is the diagnostic. */
    private fun messagesOf(vararg body: String): List<String> =
        TestSupport.messages(TestSupport.compile(program(*body)))

    // ---------------------------------------------------------------- the three-part loop

    @Test
    fun `a three-part loop counts up`() {
        val output = run(
            "var total: int = 0",
            "for (i: int = 0; i < 5; i++) {",
            "    total = total + i",
            "}",
            "println(total)",
        )
        assertEquals(listOf("10"), output)
    }

    @Test
    fun `the update may decrement and the condition may count down`() {
        val output = run(
            "var total: int = 0",
            "for (i: int = 3; i > 0; i--) {",
            "    total = total + 1",
            "}",
            "println(total)",
        )
        assertEquals(listOf("3"), output)
    }

    @Test
    fun `a condition that is false at the start stops the loop before it runs`() {
        val output = run(
            "var total: int = 0",
            "for (i: int = 10; i < 5; i++) {",
            "    total = total + 1",
            "}",
            "println(total)",
        )
        assertEquals(listOf("0"), output)
    }

    @Test
    fun `the update may be an assignment`() {
        val output = run(
            "var total: int = 0",
            "for (i: int = 1; i < 100; i = i * 2) {",
            "    total = total + 1",
            "}",
            "println(total)",
        )
        // 1, 2, 4, 8, 16, 32, 64 — seven passes, then 128 fails the test.
        assertEquals(listOf("7"), output)
    }

    @Test
    fun `the loop variable is invisible after the loop`() {
        val messages = messagesOf(
            "for (i: int = 0; i < 1; i++) {",
            "    println(i)",
            "}",
            "println(i)",
        )
        assertTrue(
            messages.any { "unresolved reference 'i'" in it },
            "the loop variable should not outlive its loop, but the errors were: $messages",
        )
    }

    @Test
    fun `two loops in one method may each declare the same name`() {
        val output = run(
            "for (i: int = 0; i < 2; i++) {",
            "    println(i)",
            "}",
            "for (i: int = 10; i < 12; i++) {",
            "    println(i)",
            "}",
        )
        assertEquals(listOf("0", "1", "10", "11"), output)
    }

    @Test
    fun `a condition must be a bool`() {
        val messages = messagesOf(
            "for (i: int = 0; i; i++) {",
            "    println(i)",
            "}",
        )
        assertTrue(
            messages.any { "int" in it && "bool" in it },
            "a non-bool condition should be refused, but the errors were: $messages",
        )
    }

    @Test
    fun `an endless loop may leave through a return`() {
        // `for (;;)` is the idiomatic endless loop: it never falls out, so the `return` inside is the
        // only way the function answers.
        val output = run(
            "var i: int = 0",
            "for (;;) {",
            "    i++",
            "    if (i > 10) {",
            "        return",
            "    }",
            "}",
        )
        assertEquals(emptyList(), output)
    }

    @Test
    fun `an endless loop cannot fall through, so a function ending in one needs no return`() {
        val program = TestSupport.loadWithLibrary(
            TestSupport.file(
                "class Main() {",
                "    static {",
                "        fun firstOverTen(): int {",
                "            var i: int = 0",
                "            for (;;) {",
                "                i++",
                "                if (i > 10) {",
                "                    return i",
                "                }",
                "            }",
                "        }",
                "",
                "        fun main() {",
                "            println(firstOverTen())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(emptyList(), TestSupport.messages(program.result))
        val output = program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") }
        assertEquals(listOf("11"), output.lines().filter { it.isNotEmpty() })
    }

    @Test
    fun `the initializer may be left out when the variable already exists`() {
        val output = run(
            "var i: int = 0",
            "for (; i < 3; i++) {",
            "    println(i)",
            "}",
            "println(i)",
        )
        assertEquals(listOf("0", "1", "2", "3"), output)
    }

    @Test
    fun `an update that is neither an increment nor an assignment is refused`() {
        val messages = messagesOf(
            "for (i: int = 0; i < 3; i * 2) {",
            "    println(i)",
            "}",
        )
        assertTrue(
            messages.any { "update" in it },
            "a non-assigning update should be refused, but the errors were: $messages",
        )
    }

    // ---------------------------------------------------------------- ++ and --

    @Test
    fun `increment and decrement work on ints and longs`() {
        val output = run(
            "var i: int = 1",
            "i++",
            "println(i)",
            "i--",
            "println(i)",
            "var l: long = 5",
            "l++",
            "println(l)",
            "l--",
            "println(l)",
        )
        assertEquals(listOf("2", "1", "6", "5"), output)
    }

    @Test
    fun `increment of a bool is refused`() {
        val messages = messagesOf(
            "var flag: bool = false",
            "flag++",
            "println(flag)",
        )
        assertTrue(
            messages.any { "whole-number" in it },
            "incrementing a bool should be refused, but the errors were: $messages",
        )
    }

    @Test
    fun `increment of a read-only variable is refused`() {
        val messages = messagesOf(
            "val fixed: int = 1",
            "fixed++",
            "println(fixed)",
        )
        assertTrue(
            messages.any { "read-only" in it },
            "incrementing a val should be refused, but the errors were: $messages",
        )
    }

    // ---------------------------------------------------------------- for ... in over an array


    @Test
    fun `for-in walks an int array`() {
        val output = run(
            intArrayOf(10, 20, 30, 40),
            emptyArray(),
            LongArray(0),
            "var total: int = 0",
            "for (element: int in ns) {",
            "    total = total + element",
            "}",
            "println(total)",
        )
        assertEquals(listOf("100"), output)
    }

    @Test
    fun `for-in walks a string array`() {
        val output = run(
            IntArray(0),
            arrayOf("miao", "nya"),
            LongArray(0),
            "for (name: string in xs) {",
            "    println(name)",
            "}",
        )
        assertEquals(listOf("miao", "nya"), output)
    }

    @Test
    fun `for-in over an empty array runs zero times`() {
        val output = run(
            IntArray(0),
            emptyArray(),
            LongArray(0),
            "var total: int = 0",
            "for (element: int in ns) {",
            "    total = total + 1",
            "}",
            "println(total)",
        )
        assertEquals(listOf("0"), output)
    }

    @Test
    fun `for-in over a long array binds a long`() {
        val output = run(
            IntArray(0),
            emptyArray(),
            longArrayOf(7, 8),
            "var total: long = 0",
            "for (element: long in ls) {",
            "    total = total + element",
            "}",
            "println(total)",
        )
        assertEquals(listOf("15"), output)
    }

    @Test
    fun `a long element bound to an int is refused, as an assignment would be`() {
        // Neko never converts a value of one numeric type to another, so the loop variable has to be
        // the array's own element type; a widening here would contradict every other assignment.
        val messages = messagesOf(
            "for (element: int in ls) {",
            "    println(element)",
            "}",
        )
        assertTrue(
            messages.any { "long" in it && "int" in it },
            "binding a long element to an int should be refused, but the errors were: $messages",
        )
    }

    @Test
    fun `the loop variable of for-in is invisible after the loop`() {
        val messages = messagesOf(
            "for (element: int in ns) {",
            "    println(element)",
            "}",
            "println(element)",
        )
        assertTrue(
            messages.any { "unresolved reference 'element'" in it },
            "the element variable should not outlive its loop, but the errors were: $messages",
        )
    }

    @Test
    fun `the loop variable of for-in is read-only`() {
        val messages = messagesOf(
            "for (element: int in ns) {",
            "    element = 5",
            "}",
        )
        assertTrue(
            messages.isNotEmpty(),
            "assigning to the loop variable should be refused, but the program compiled",
        )
    }

    @Test
    fun `an element type that does not match the array is refused`() {
        val messages = messagesOf(
            "for (element: int in xs) {",
            "    println(element)",
            "}",
        )
        assertTrue(
            messages.any { "string" in it && "int" in it },
            "binding a string element to an int should be refused, but the errors were: $messages",
        )
    }

    @Test
    fun `nested for-in loops both bind their own variable`() {
        val output = run(
            intArrayOf(1, 2),
            emptyArray(),
            LongArray(0),
            "var total: int = 0",
            "for (a: int in ns) {",
            "    for (b: int in ns) {",
            "        total = total + a * b",
            "    }",
            "}",
            "println(total)",
        )
        // (1 + 2) * (1 + 2) = 9
        assertEquals(listOf("9"), output)
    }
    // ---------------------------------------------------------------- for ... in over an Iterable

    /**
     * A program implementing the standard library's two interfaces, written the way a program would:
     * `iterator()` answers a fresh cursor, so each loop sees the whole sequence.
     */
    private fun iterableProgram(vararg body: String): String = TestSupport.file(
        "class Cursor: Iterator {",
        "    val items: string[]",
        "    val upto: int",
        "    var index: int = 0",
        "",
        "    constructor(items: string[], upto: int) {",
        "        this.items = items",
        "        this.upto = upto",
        "    }",
        "",
        "    fun hasNext(): bool {",
        "        return index < upto",
        "    }",
        "",
        "    fun next(): Object {",
        "        val value: string = items[index]",
        "        index++",
        "        return value",
        "    }",
        "}",
        "",
        "class Words: Iterable {",
        "    val items: string[]",
        "    val upto: int",
        "",
        "    constructor(items: string[], upto: int) {",
        "        this.items = items",
        "        this.upto = upto",
        "    }",
        "",
        "    fun iterator(): Iterator {",
        "        return Cursor(items, upto)",
        "    }",
        "}",
        "",
        "class Main() {",
        "    static {",
        "        fun main(ws: string[]) {",
        *body.map { "            $it" }.toTypedArray(),
        "        }",
        "    }",
        "}",
    )

    @Test
    fun `for-in walks a custom iterable`() {
        val output = runIterable(
            arrayOf("miao", "nya", "purr"),
            "val words: Words = Words(ws, 3)",
            "var total: int = 0",
            "for (word: string in words) {",
            "    println(word)",
            "    total++",
            "}",
            "println(total)",
        )
        assertEquals(listOf("miao", "nya", "purr", "3"), output)
    }

    @Test
    fun `for-in asks for an iterator once per loop`() {
        // A fresh iterator per pass would restart the sequence; the lowering asks once, before the
        // first test, so two loops each see all three elements.
        val output = runIterable(
            arrayOf("a", "b", "c"),
            "val words: Words = Words(ws, 3)",
            "var passes: int = 0",
            "for (word: string in words) {",
            "    passes++",
            "}",
            "for (word: string in words) {",
            "    passes++",
            "}",
            "println(passes)",
        )
        assertEquals(listOf("6"), output)
    }

    @Test
    fun `for-in over something with no elements is refused`() {
        val messages = messagesOf(
            "val n: int = 5",
            "for (element: int in n) {",
            "    println(element)",
            "}",
        )
        assertTrue(
            messages.any { "no elements" in it },
            "iterating an int should be refused, but the errors were: $messages",
        )
    }

    private fun runIterable(words: Array<String>, vararg body: String): List<String> {
        val program = TestSupport.loadWithLibrary(iterableProgram(*body))
        assertEquals(emptyList(), TestSupport.messages(program.result))
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main", words)
        }
        return output.lines().filter { it.isNotEmpty() }
    }
}
