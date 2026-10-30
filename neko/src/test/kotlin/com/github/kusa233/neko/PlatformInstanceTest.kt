package com.github.kusa233.neko

import com.github.kusa233.neko.source.SourceFile
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `list.add(1)`, `list.size()` — an **instance** member of a JDK class, called on a value.
 *
 * The instance counterpart of [PlatformStaticTest]. A class the program does not declare has no
 * `ClassSymbol`, so a member call on a value of one used to be refused with "is not declared in this
 * file, so its members cannot be checked" — a message about the *compiler's* knowledge rather than
 * about the program, and one that left no way to call `java.util.ArrayList.add` at all.
 *
 * Reflection answers instead, exactly as it already did for static members. The subtlety is on the
 * argument side: the JDK declares `add(Object)`, so an `int` argument has to be **boxed** on the way
 * in. That makes this worth a run-time test rather than a compile-only one — a missing box compiles
 * cleanly and is rejected by the JVM verifier when the class loads.
 */
class PlatformInstanceTest {

    @Test
    fun `an instance method of a declared platform type is called through its value`() {
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            list: ArrayList<int> = ArrayList<int>()",
            "            println(list.isEmpty())",
            "            list.add(1)",
            "            list.add(2)",
            "            println(list.size())",
            "            println(list)",
            "        }",
            "    }",
            "}",
        )

        assertEquals(
            emptyList(),
            TestSupport.errors(TestSupport.compile(program)).map { it.message },
            "a JDK instance call should compile",
        )
        assertEquals(listOf("true", "2", "[1, 2]"), run(program))
    }

    /**
     * An argument is boxed to match the JDK's declared parameter, not passed as a primitive.
     *
     * `ArrayList.add` takes `Object`, so `add(1)` has to box. If the symbol recorded the *argument*
     * type instead of the reflected one, the descriptor would be `(I)Z` — a method that does not exist
     * — and the call would be rejected at load time rather than at compile time.
     */
    @Test
    fun `a primitive argument is boxed to the JDK parameter type`() {
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            list: ArrayList<int> = ArrayList<int>()",
            "            list.add(42)",
            "            println(list.get(0))",
            "        }",
            "    }",
            "}",
        )
        assertEquals(listOf("42"), run(program))
    }

    /**
     * A receiver whose type is a JDK **interface** is dispatched with `invokeinterface`.
     *
     * The JVM has no `invokevirtual` for an interface — using one is a verification error — and the
     * usual way this compiler decides is the symbol's enclosing class, which a JDK method does not
     * have. The fact has to be carried from the reflected class instead.
     */
    @Test
    fun `a JDK interface receiver is dispatched with invokeinterface`() {
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            list: List = Collections.emptyList()",
            "            println(list.isEmpty())",
            "            println(list.size())",
            "        }",
            "    }",
            "}",
        )
        assertEquals(listOf("true", "0"), run(program))
    }

    /** A member the JDK class does not have is reported as such, at the member name. */
    @Test
    fun `an unknown member of a JDK class is reported`() {
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            list: ArrayList<int> = ArrayList<int>()",
            "            list.nope(1)",
            "        }",
            "    }",
            "}",
        )

        val messages = TestSupport.messages(TestSupport.compile(program))
        assertTrue(
            messages.any { "ArrayList has no member named 'nope'" in it },
            "expected the missing member to be named: $messages",
        )
    }

    /**
     * An argument that the receiver's own type arguments rule out is refused.
     *
     * `ArrayList` declares `add(E)`, and `E` erases to `Object` — the one type every value in this
     * language is assignable to. Reading the erased signature therefore approved `add("awa")` on an
     * `ArrayList<int>`, and the call site then boxed the string onto a stack slot the descriptor
     * declared as `Object`: compiles cleanly, and the list quietly becomes `["awa"]` while every
     * `get` promises an `int`. The written `int` is what the call has to be held to.
     */
    @Test
    fun `an argument against the receiver's type argument is refused`() {
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            list: ArrayList<int> = ArrayList<int>()",
            "            list.add(\"awa\")",
            "        }",
            "    }",
            "}",
        )

        val messages = TestSupport.messages(TestSupport.compile(program))
        assertTrue(
            messages.any { "declared as E, which is int here, but has type string" in it },
            "expected the argument to be held to the written type argument: $messages",
        )
    }

    /**
     * The same call on an `ArrayList` that names no argument is still accepted.
     *
     * An erased receiver has said nothing for the argument to disagree with, so the erased signature
     * is the only promise in play and `add(1)` is the boxed value it declares. Refusing this would
     * make the erased spelling unusable, which is what `ArrayList = ArrayList<int>()` relies on.
     */
    @Test
    fun `an argument against a receiver that names no type argument is accepted`() {
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            list: ArrayList = ArrayList()",
            "            list.add(\"awa\")",
            "            list.add(1)",
            "            println(list)",
            "        }",
            "    }",
            "}",
        )

        assertEquals(emptyList(), TestSupport.errors(TestSupport.compile(program)).map { it.message })
        assertEquals(listOf("[awa, 1]"), run(program))
    }

    /**
     * The written type arguments *filter* the overloads; they do not choose among them.
     *
     * `ArrayList` declares both `remove(int)` and `remove(Object)`, and an `int` argument satisfies
     * the receiver's `E` in the second just as well as it satisfies the first — so a check that only
     * asked "does this argument match what the receiver was written with?" left *two* candidates
     * standing and picked whichever reflection listed first. The two overloads differ in what they
     * return: `remove(int)` the removed element, `remove(Object)` a `boolean`. The erased passes still
     * have to make the decision, exactly as they do for a receiver that names no argument.
     */
    @Test
    fun `an overload is still chosen by its erased parameter among the ones the type argument allows`() {
        val program = TestSupport.file(
            "import java.util",
            "",
            "class Uses {",
            "    static {",
            "        fun main() {",
            "            list: ArrayList<int> = ArrayList<int>()",
            "            list.add(123)",
            "            println(list.remove(0))",
            "            println(list.size())",
            "        }",
            "    }",
            "}",
        )

        assertEquals(emptyList(), TestSupport.errors(TestSupport.compile(program)).map { it.message })
        // `[123]` and `0`; a `remove(Object)` binding would print `true` and then `1`.
        assertEquals(listOf("123", "0"), run(program))
    }

    /** Compiles [program], runs its `main()`, and returns what it printed, one line per entry. */
    private fun run(program: String): List<String> {
        val directory = Files.createTempDirectory("neko-platform-instance")
        val result = Compiler.compileProgram(
            listOf(SourceFile.of("Uses.neko", program)),
            outputDirectory = directory,
        )
        check(!result.hasErrors) {
            "expected the program to compile, but it reported:\n" +
                result.diagnostics.joinToString("\n") { it.toString() }
        }

        val loader = URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader)
        val type = Class.forName("com.github.kusa233.test.Uses", true, loader)
        // A Neko `main()` with no parameters is emitted alongside the synthetic `main(String[])` the
        // launcher uses, so the arity is what picks this one out.
        val main = type.declaredMethods.single { it.name == "main" && it.parameterCount == 0 }

        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            main.invoke(null)
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .trim()
            .lines()
    }
}
