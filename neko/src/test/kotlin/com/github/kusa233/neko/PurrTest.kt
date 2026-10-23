package com.github.kusa233.neko

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for `purr` — a critical section backed by a `java.util.concurrent.locks.StampedLock`.
 *
 * The interesting failures here are not type errors but *silent* ones: a lock left held, or two
 * threads writing without seeing each other. So these run the compiled program and check what it did,
 * and the concurrency ones use real threads — a single-threaded test cannot tell a working lock from a
 * missing one.
 */
class PurrTest {

    private fun file(vararg lines: String) = TestSupport.file(*lines)

    /**
     * Runs [body] on [count] threads at once, letting any failure surface as a test failure.
     *
     * The concurrency tests drive the *compiled* object from here rather than from Neko code, because
     * Neko's function types compile to interfaces of its own making and so cannot be handed to
     * `java.lang.Thread`, whose `Runnable` they do not implement. Calling the compiled method
     * reflectively from real threads exercises exactly the same lock.
     */
    private fun concurrently(count: Int, body: (Int) -> Unit) {
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val threads = (0 until count).map { index ->
            Thread {
                ready.countDown()
                start.await()
                try {
                    body(index)
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
        }
        threads.forEach { it.start() }
        // Every thread waits on one latch, so they start together and actually contend, instead of
        // running one after another and never overlapping.
        ready.await(10, TimeUnit.SECONDS)
        start.countDown()
        threads.forEach { it.join(30_000) }
        assertTrue(threads.none { it.isAlive }, "a thread did not finish: the lock was never released")
        failures.firstOrNull()?.let { throw it }
    }

    /**
     * A `Counter` whose `increment` reads and writes the field in two steps, which is exactly the race
     * a lock is there to remove: without one, threads interleave between the read and the write and
     * the total comes out short. That is what lets a test tell a held lock from a missing one.
     */
    private fun counterProgram(mode: String): String = file(
        "class Counter() {",
        "    var total: int = 0",
        "",
        "    fun increment() {",
        "        purr$mode {",
        "            current: int = this.total",
        "            this.total = current + 1",
        "        }",
        "    }",
        "",
        "    fun get(): int {",
        "        return this.total",
        "    }",
        "}",
        "",
        "class Main() {",
        "    static {",
        "        fun main() { }",
        "    }",
        "}",
    )

    @Test
    fun `a purr block makes concurrent increments add up`() {
        // Eight threads, 500 increments each, read-then-write. Without the lock this loses updates and
        // lands under 4000; with it the answer is exact. That difference is the whole feature.
        val program = TestSupport.load(counterProgram(""))
        val type = program.typeOf("com.github.kusa233.test.Counter")
        val instance = type.getDeclaredConstructor().newInstance()
        val increment = type.getMethod("increment")

        concurrently(8) { repeat(500) { increment.invoke(instance) } }

        assertEquals(4000, type.getMethod("get").invoke(instance), "every increment should count once")
    }

    @Test
    fun `a read lock excludes writers`() {
        // The mode is what separates the two: readers share, but a writer excludes them. Counting is
        // the wrong instrument here — two readers hold the lock at once, so a read-modify-write under
        // `purr read` is *expected* to race and a stale counter would report a false overlap. What the
        // mode promises is only about meeting a writer, so each side raises its own flag and looks at
        // the other's. One thread each, so a flag is written only by its owner and the booleans say
        // exactly what they mean.
        val program = TestSupport.load(
            file(
                "class Gate() {",
                "    var reading: bool = false",
                "    var writing: bool = false",
                "    var overlap: bool = false",
                "",
                "    fun read() {",
                "        purr read {",
                "            this.reading = true",
                "            n: int = 0",
                "            while (n < 2000) {",
                "                n = n + 1",
                "            }",
                "            if (this.writing) {",
                "                this.overlap = true",
                "            }",
                "            this.reading = false",
                "        }",
                "    }",
                "",
                "    fun write() {",
                "        purr {",
                "            this.writing = true",
                "            n: int = 0",
                "            while (n < 2000) {",
                "                n = n + 1",
                "            }",
                "            if (this.reading) {",
                "                this.overlap = true",
                "            }",
                "            this.writing = false",
                "        }",
                "    }",
                "",
                "    fun sawOverlap(): bool {",
                "        return this.overlap",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        val type = program.typeOf("com.github.kusa233.test.Gate")
        val instance = type.getDeclaredConstructor().newInstance()
        val read = type.getMethod("read")
        val write = type.getMethod("write")

        concurrently(2) { index ->
            val method = if (index == 0) read else write
            repeat(300) { method.invoke(instance) }
        }

        assertEquals(
            false,
            type.getMethod("sawOverlap").invoke(instance),
            "a reader and a writer were inside the same lock at once",
        )
    }

    @Test
    fun `two readers hold the read lock at the same time`() {
        // The point of `purr read`: readers do not wait for each other. Each reader waits for the
        // other to arrive before leaving, which can only succeed if both are inside at once — a
        // write lock here would deadlock and time out.
        val program = TestSupport.load(
            file(
                "class Gate() {",
                "    var inside: int = 0",
                "    var met: bool = false",
                "",
                "    fun enter() {",
                "        purr read {",
                "            this.inside = this.inside + 1",
                "            n: int = 0",
                "            // Spin until the other reader has arrived. Bounded, so a failure ends rather",
                "            // than hanging.",
                "            while (n < 2000000 && this.inside < 2) {",
                "                n = n + 1",
                "            }",
                "            if (this.inside >= 2) {",
                "                this.met = true",
                "            }",
                "        }",
                "    }",
                "",
                "    fun didMeet(): bool {",
                "        return this.met",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        val type = program.typeOf("com.github.kusa233.test.Gate")
        val instance = type.getDeclaredConstructor().newInstance()
        val enter = type.getMethod("enter")

        concurrently(2) { enter.invoke(instance) }

        assertEquals(
            true,
            type.getMethod("didMeet").invoke(instance),
            "both readers should have been inside the block at the same time",
        )
    }

    @Test
    fun `a purr block releases the lock when its body returns`() {
        // A `return` inside the block leaves the method without running the code after the block, so
        // the unlock has to be emitted before the `RETURN` itself. If it is not, the lock stays held —
        // and the second call, on the same thread and the same object, blocks forever.
        val program = TestSupport.load(
            file(
                "class Guarded() {",
                "    fun early(): int {",
                "        purr {",
                "            return 7",
                "        }",
                "    }",
                "",
                "    fun again(): int {",
                "        purr {",
                "            return 9",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            g: Guarded = Guarded()",
                "            println(g.early())",
                "            println(g.again())",
                "        }",
                "    }",
                "}",
            ),
        )
        // A timeout, because the failure mode is a deadlock rather than a wrong answer.
        val output = program.capturingOutput {
            val thread = Thread { program.invoke("com.github.kusa233.test.Main", "main") }
            thread.isDaemon = true
            thread.start()
            thread.join(10_000)
            assertTrue(!thread.isAlive, "the program deadlocked: a 'return' skipped the unlock")
        }
        assertEquals("7\n9\n", output, "both calls answer, which means both locks were released")
    }

    @Test
    fun `a purr block releases the lock when its body throws`() {
        // The catch-all handler is what covers this. Reaching the second call at all proves the first
        // one's lock was released on the way out of the exception.
        val program = TestSupport.load(
            file(
                "class Guarded() {",
                "    fun risky() {",
                "        purr {",
                "            nya 1 == 2",
                "        }",
                "    }",
                "",
                "    fun safe(): int {",
                "        purr {",
                "            return 1",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            g: Guarded = Guarded()",
                "            nya g.risky() {",
                "                println(\"caught\")",
                "            }",
                "            println(g.safe())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertTrue(
            output.contains("caught") && output.contains("1"),
            "the lock should be released by the throw path, got: $output",
        )
    }

    @Test
    fun `purr class and purr together take two different locks`() {
        // The two locks are separate, so nesting them is legal — and this is the shape a class-wide
        // guard around a per-object one has. A single lock would deadlock here, since StampedLock is
        // not reentrant.
        val program = TestSupport.load(
            file(
                "class Boxed() {",
                "    var value: int = 0",
                "",
                "    fun update(): int {",
                "        purr class {",
                "            purr {",
                "                this.value = this.value + 1",
                "                return this.value",
                "            }",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            b: Boxed = Boxed()",
                "            println(b.update())",
                "            println(b.update())",
                "        }",
                "    }",
                "}",
            ),
        )
        val output = program.capturingOutput {
            program.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("1\n2\n", output)
    }

    @Test
    fun `the class lock lets two objects exclude each other`() {
        // What the class lock is for: two different objects of the same class still take turns. A
        // static field is the thing they share, so a static field is what the test counts.
        val program = TestSupport.load(
            file(
                "class Shared() {",
                "    static {",
                "        var total: int = 0",
                "",
                "        fun getTotal(): int {",
                "            return total",
                "        }",
                "    }",
                "",
                "    fun bump() {",
                "        purr class {",
                "            current: int = Shared.total",
                "            Shared.total = current + 1",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        val type = program.typeOf("com.github.kusa233.test.Shared")
        val objects = (0 until 4).map { type.getDeclaredConstructor().newInstance() }
        val bump = type.getMethod("bump")

        // Each thread uses its *own* object, so an instance lock would give every thread its own lock
        // and exclude nothing — only a shared class lock makes the static count come out exact.
        concurrently(4) { index ->
            val subject = objects[index]
            repeat(250) { bump.invoke(subject) }
        }

        assertEquals(
            1000,
            type.getMethod("getTotal").invoke(null),
            "the class lock should exclude across objects",
        )
    }

    @Test
    fun `an instance lock in a static function is refused`() {
        // There is no `this` there, so there is no instance lock to take.
        val result = TestSupport.compile(
            file(
                "class Main() {",
                "    static {",
                "        fun helper() {",
                "            purr {",
                "                println(1)",
                "            }",
                "        }",
                "",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("has no instance") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a static function may use the class lock`() {
        val program = TestSupport.load(
            file(
                "class Main() {",
                "    static {",
                "        fun helper(): int {",
                "            purr class {",
                "                return 42",
                "            }",
                "        }",
                "",
                "        fun main() {",
                "            println(helper())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "42\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `the same lock cannot be taken twice`() {
        // StampedLock is not reentrant, so this deadlocks at run time with no error. Catching it at
        // compile time is the whole point.
        val result = TestSupport.compile(
            file(
                "class Boxed() {",
                "    fun nested() {",
                "        purr {",
                "            purr {",
                "                println(1)",
                "            }",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("block forever") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `a read inside a write on the same lock is also refused`() {
        // The mode does not make a second acquisition safe: the JVM has no owner to notice the same
        // thread asking again, and a write lock is exclusive even against the thread that holds it.
        val result = TestSupport.compile(
            file(
                "class Boxed() {",
                "    fun nested() {",
                "        purr {",
                "            purr read {",
                "                println(1)",
                "            }",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() { }",
                "    }",
                "}",
            ),
        )
        assertTrue(
            TestSupport.errors(result).any { it.message.contains("block forever") },
            "unexpected messages: ${TestSupport.messages(result)}",
        )
    }

    @Test
    fun `sibling purr blocks are not nested`() {
        // Two blocks one after the other are fine: the first has released by the time the second runs.
        val program = TestSupport.load(
            file(
                "class Boxed() {",
                "    var value: int = 0",
                "",
                "    fun twice(): int {",
                "        purr {",
                "            this.value = 1",
                "        }",
                "        purr {",
                "            this.value = this.value + 1",
                "        }",
                "        return this.value",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            b: Boxed = Boxed()",
                "            println(b.twice())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "2\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `a declaration with no purr gets no lock field`() {
        // The lock is created on demand, so a class that never uses `purr` should carry nothing.
        val program = TestSupport.load(
            file(
                "class Plain() {",
                "    fun value(): int {",
                "        return 1",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            println(Plain().value())",
                "        }",
                "    }",
                "}",
            ),
        )
        val fields = program.typeOf("com.github.kusa233.test.Plain").declaredFields
        assertTrue(
            fields.none { it.type.name.contains("StampedLock") },
            "a class with no 'purr' should have no lock, got ${fields.map { it.name }}",
        )
    }

    @Test
    fun `a purr block gets a StampedLock field`() {
        val program = TestSupport.load(
            file(
                "class Guarded() {",
                "    fun value(): int {",
                "        purr {",
                "            return 1",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            println(Guarded().value())",
                "        }",
                "    }",
                "}",
            ),
        )
        val type = program.typeOf("com.github.kusa233.test.Guarded")
        val lock = type.declaredFields.singleOrNull {
            it.type.name == "java.util.concurrent.locks.StampedLock"
        }
        assertTrue(lock != null, "expected a StampedLock field, got ${type.declaredFields.map { it.name }}")
        assertTrue(
            java.lang.reflect.Modifier.isFinal(lock.modifiers),
            "the lock field should be final: it is created once and never replaced",
        )
    }

    @Test
    fun `a class lock is static and shared`() {
        // The class lock has to be static, or two objects would take two different locks and exclude
        // nothing. A static field is what makes every instance and every static method meet on one.
        val program = TestSupport.load(
            file(
                "class Shared() {",
                "    fun bump(): int {",
                "        purr class {",
                "            return 1",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            println(Shared().bump())",
                "        }",
                "    }",
                "}",
            ),
        )
        val type = program.typeOf("com.github.kusa233.test.Shared")
        val lock = type.declaredFields.singleOrNull {
            it.type.name == "java.util.concurrent.locks.StampedLock"
        }
        assertTrue(lock != null, "expected a StampedLock field")
        assertTrue(
            java.lang.reflect.Modifier.isStatic(lock.modifiers),
            "the class lock should be static, got modifiers ${lock.modifiers}",
        )
    }

    @Test
    fun `an enum's own method can use an instance purr block`() {
        // An enum's only constructor is the private one the compiler emits, not one from
        // `emitConstructors`, so the instance lock has to be built there too — otherwise the field is
        // null and taking it throws a NullPointerException.
        val program = TestSupport.load(
            file(
                "miao class Color(val code: int) {",
                "    RED(1),",
                "    GREEN(2);",
                "",
                "    fun doubled(): int {",
                "        purr {",
                "            return this.code * 2",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            println(Color.GREEN.doubled())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "4\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `an enum's own method can use a purr class block`() {
        // The class lock is built in the enum's `<clinit>`, which the compiler writes itself rather
        // than through `emitStaticInitializer`.
        val program = TestSupport.load(
            file(
                "miao class Color {",
                "    RED,",
                "    GREEN;",
                "",
                "    fun count(): int {",
                "        purr class {",
                "            return 2",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            println(Color.RED.count())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "2\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }

    @Test
    fun `purr read is accepted and purr write is the default`() {        // Both spellings of the mode have to work, and the block after `purr read` parses as the same
        // statement rather than as a variable named `read`.
        val program = TestSupport.load(
            file(
                "class Boxed() {",
                "    var value: int = 3",
                "",
                "    fun look(): int {",
                "        purr read {",
                "            return this.value",
                "        }",
                "    }",
                "}",
                "",
                "class Main() {",
                "    static {",
                "        fun main() {",
                "            println(Boxed().look())",
                "        }",
                "    }",
                "}",
            ),
        )
        assertEquals(
            "3\n",
            program.capturingOutput { program.invoke("com.github.kusa233.test.Main", "main") },
        )
    }
}
