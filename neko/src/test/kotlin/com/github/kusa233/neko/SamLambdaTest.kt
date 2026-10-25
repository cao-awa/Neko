package com.github.kusa233.neko

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for writing a lambda where a *single-method interface* is expected.
 *
 * The language grew up with function types: `f: [int] -> int = { x -> ... }` produces a generated
 * interface with an `invoke`, and that is still how a lambda used as a value works. What it could
 * not do was stand in for a named interface — `runIt({ println(1) })` against a `Runnable` parameter
 * was simply unparseable, because the parser read `println` as a parameter name and then demanded an
 * arrow after it.
 *
 * Two things had to become true. The parser asks whether an `->` appears before the matching `}`
 * before treating a `{` as a parameter list, which is what makes `{ ... }` with no arrow a body. And
 * the analyzer asks a single-method interface for the one abstract method's signature, so the body
 * is checked against `run()`'s parameters and result rather than against nothing.
 *
 * The interface's method name matters at code-generation time and is not always `invoke` — it is
 * `run` for `Runnable` — which is why the analyzer hands the generator the name instead of letting
 * it assume one.
 */
class SamLambdaTest {

    private fun program(vararg lines: String): String = buildString {
        append("locate com.github.kusa233.neko.sample\n")
        append('\n')
        for (line in lines) {
            append(line)
            append('\n')
        }
    }

    /**
     * The form the whole feature exists for: a lambda written straight into an argument list.
     *
     * The interface is declared here rather than taken from the JDK so the test says what it is
     * checking — the single-method rule and the generated method name — without depending on
     * `Runnable` being reachable as a Neko type, which is a separate question.
     */
    @Test
    fun `a lambda can be passed where a single-method interface is expected`() {
        val loaded = TestSupport.load(
            program(
                "interface Action {",
                "    fun perform(times: int)",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun twice(a: Action) {",
                "            a.perform(2)",
                "        }",
                "",
                "        fun main() {",
                "            twice({ n ->",
                "                println(n)",
                "            })",
                "        }",
                "    }",
                "}",
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("2", printed.trim())
    }

    /**
     * A lambda with nothing to take needs no arrow, because there is nothing to separate it from.
     *
     * This is the `{ }` / `{ println(1) }` form. It used to be a parse error, and the error message —
     * "expected '->' after the lambda parameters" — described a parameter list that the author had
     * not written and did not mean.
     */
    @Test
    fun `a zero-parameter lambda may omit the arrow`() {
        val loaded = TestSupport.load(
            program(
                "interface Task {",
                "    fun go()",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun performIt(t: Task) {",
                "            t.go()",
                "        }",
                "",
                "        fun main() {",
                "            performIt({",
                "                println(\"no arrow\")",
                "            })",
                "        }",
                "    }",
                "}",
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("no arrow", printed.trim())
    }

    /** The empty body is the shortest lambda in the language, and it has to parse too. */
    @Test
    fun `an empty zero-parameter lambda parses`() {
        val loaded = TestSupport.load(
            program(
                "interface Task {",
                "    fun go()",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun performIt(t: Task) {",
                "            t.go()",
                "        }",
                "",
                "        fun main() {",
                "            performIt({ })",
                "            println(\"after\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("after", printed.trim())
    }

    /**
     * The generated method is the interface's, not a synthetic `invoke`.
     *
     * `a.perform(2)` calls through the interface, so this passing is also the proof that the lambda
     * implemented `perform` rather than something of its own: a class missing it would be abstract,
     * and the JVM would refuse to instantiate the lambda at all.
     */
    @Test
    fun `the generated class implements the interface's own method`() {
        val loaded = TestSupport.load(
            program(
                "interface Greeter {",
                "    fun greet(name: string)",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun use(g: Greeter) {",
                "            g.greet(\"neko\")",
                "        }",
                "",
                "        fun main() {",
                "            use({ who ->",
                "                println(who)",
                "            })",
                "        }",
                "    }",
                "}",
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("neko", printed.trim())
    }

    /**
     * A `yield` inside the lambda is what the interface's method returns.
     *
     * The result type of the abstract method is the only thing that can tell the body what to
     * produce, so this checks that it was taken from the interface rather than left unknown.
     */
    @Test
    fun `a lambda can return a value through the interface's method`() {
        val loaded = TestSupport.load(
            program(
                "interface Doubler {",
                "    fun apply(n: int): int",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun use(d: Doubler): int {",
                "            return d.apply(21)",
                "        }",
                "",
                "        fun main() {",
                "            println(use({ n ->",
                "                yield n * 2",
                "            }))",
                "        }",
                "    }",
                "}",
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("42", printed.trim())
    }

    /**
     * An interface with *two* abstract methods is not a target for a lambda.
     *
     * There would be no way to say which one the body belonged to, so the lambda keeps its old
     * meaning — a value of no known function type — and the body is then checked against nothing,
     * which is what "never yields a value" reports. The argument also fails to match the parameter.
     * This is the boundary of the feature, and it is worth pinning so that a later change does not
     * quietly start guessing.
     */
    @Test
    fun `a two-method interface is not a lambda target`() {
        val result = TestSupport.compile(
            program(
                "interface Pair {",
                "    fun first()",
                "    fun second()",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun use(p: Pair) {",
                "            p.first()",
                "        }",
                "",
                "        fun main() {",
                "            use({ println(1) })",
                "        }",
                "    }",
                "}",
            ),
        )
        // Two diagnostics land, and either alone would prove the point: the lambda had no type to be
        // checked against, and the argument therefore does not match the parameter it was passed to.
        // The first is asserted because it is the one that explains *why*.
        assertTrue(
            TestSupport.errors(result).any { "never yields a value" in it.message },
            "expected the lambda to be refused, but got: ${TestSupport.messages(result)}",
        )
        assertTrue(
            TestSupport.errors(result).any { "is declared as Pair" in it.message },
            "expected the argument type to be reported, but got: ${TestSupport.messages(result)}",
        )
    }

    /**
     * The `Runnable` path works too, and this is the check that its body actually runs.
     *
     * A JDK interface has no symbol — the program never compiles the JDK — so its single abstract
     * method has to be read off the loaded class, and the generated method has to be named `run`
     * rather than the `invoke` every synthetic function interface uses. Neither is visible from the
     * source side, so the assertion is made on the class that was produced: find whatever implements
     * `Runnable`, build it, and call `run`.
     *
     * Neko cannot call `r.run()` itself — a platform type's instance methods are not reachable from
     * the language — which is why the call comes from here instead. That is a separate limitation and
     * does not affect whether the lambda was built correctly.
     */
    @Test
    fun `a lambda against a JDK interface implements it and its body runs`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun take(r: Runnable) {",
                "            println(\"handed over\")",
                "        }",
                "",
                "        fun main() {",
                "            take({",
                "                println(\"inside the lambda\")",
                "            })",
                "        }",
                "    }",
                "}",
            ),
        )

        val fromNeko = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("handed over", fromNeko.trim())

        val implementations = runnableImplementationsOf(loaded)
        assertEquals(
            1,
            implementations.size,
            "expected exactly one generated Runnable, got ${implementations.map { it.name }}",
        )

        val executed = loaded.capturingOutput {
            // The cast is what picks `Runnable.run()` rather than the `run { }` scope function. The
            // constructor is package-private, like every generated lambda's, so reflection has to be
            // told to reach it — the JVM has no complaint about the *class* implementing `Runnable`.
            val constructor = implementations.single().getDeclaredConstructor()
            constructor.isAccessible = true
            (constructor.newInstance() as Runnable).run()
        }
        assertEquals("inside the lambda", executed.trim())
    }

    /**
     * A platform *constructor*'s argument gets its expected type too.
     *
     * `Thread({ ... })` is the literal shape asked for, and it is the hardest case: `Thread` is not
     * declared in the program, so its constructors are not in the symbol table and there is nothing
     * to check an argument against. The lambda would simply have no target.
     *
     * The arity is ambiguous — `Thread` has both `Thread(Runnable)` and `Thread(String)` — and the
     * only fact that can break the tie is that a lambda fits a functional-interface parameter and
     * cannot fit `String`. That is enough to pick `Thread(Runnable)` without guessing.
     *
     * `Thread`'s own methods remain uncallable from Neko, which is a separate and older limitation
     * about platform *instance* members (`"abc".length()` fails the same way). This test therefore
     * checks the one thing the feature is responsible for: the lambda was understood and built.
     */
    @Test
    fun `a lambda can be passed to a platform constructor's functional interface parameter`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            t: Thread = Thread({ println(\"thread body\") })",
                "            println(\"constructed\")",
                "        }",
                "    }",
                "}",
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("constructed", printed.trim())

        val implementations = runnableImplementationsOf(loaded)
        assertEquals(
            1,
            implementations.size,
            "expected the lambda to implement Runnable, got ${implementations.map { it.name }}",
        )
    }

    /**
     * Every class the compiler wrote that implements [Runnable], found by looking rather than naming.
     *
     * A lambda's name is generated (`Main$Lambda$0`), so a test that spelled one out would be pinning
     * an implementation detail that is free to change. Scanning the output directory asks the only
     * question that matters: did a `Runnable` get produced?
     */
    private fun runnableImplementationsOf(loaded: TestSupport.LoadedProgram): List<Class<*>> {
        val names = Files.walk(loaded.directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                .map { path ->
                    loaded.directory.relativize(path).toString()
                        .removeSuffix(".class")
                        .replace('\\', '.')
                        .replace('/', '.')
                }
                .toList()
        }
        return names.mapNotNull { name ->
            val type = loaded.typeOf(name)
            type.takeIf { Runnable::class.java.isAssignableFrom(it) }
        }
    }

    /**
     * A lambda assigned to a variable still produces a function value, not an interface instance.
     *
     * The new path must not have displaced the old one: `f: [int] -> int = { x -> ... }` has to keep
     * working, and it is the shape the whole language used before single-method interfaces were
     * accepted here.
     */
    @Test
    fun `a lambda against a function type still works`() {
        val loaded = TestSupport.load(
            program(
                "class Main {",
                "    static {",
                "        fun main() {",
                "            f: [int] -> int = { x ->",
                "                yield x + 1",
                "            }",
                "            println(f(1))",
                "        }",
                "    }",
                "}",
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("2", printed.trim())
    }

    /**
     * A lambda's captured variables still travel, whichever target it was written against.
     *
     * The capture machinery is shared with the function-type path, and this is the check that the
     * single-method branch did not lose it: the body reads an enclosing local that has to become a
     * field of the generated class.
     */
    @Test
    fun `a single-method lambda still captures enclosing variables`() {
        val loaded = TestSupport.load(
            program(
                "interface Action {",
                "    fun perform()",
                "}",
                "",
                "class Main {",
                "    static {",
                "        fun use(a: Action) {",
                "            a.perform()",
                "        }",
                "",
                "        fun main() {",
                "            greeting: string = \"captured\"",
                "            use({",
                "                println(greeting)",
                "            })",
                "        }",
                "    }",
                "}",
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.neko.sample.Main", "main")
        }
        assertEquals("captured", printed.trim())
    }
}
