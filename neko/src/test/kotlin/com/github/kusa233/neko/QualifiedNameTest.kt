package com.github.kusa233.neko

import com.github.kusa233.neko.source.SourceFile
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `import java.util.HashMap` — importing one class — and `java.util.HashMap` — writing a qualified name.
 *
 * The two are one feature read from both ends. A bare `import java.util` says "anything in this
 * package, by simple name"; `import java.util.HashMap` says "*this* class", which matters when two
 * imports both hold a name; and `java.util.HashMap` says it inline, for a file that would rather not
 * import at all. What ties them together is that the same dotted text has to be classified the same
 * way wherever it appears — as an import target, as a type, as a callee, as a field's owner — so
 * these tests deliberately exercise each position.
 *
 * `.*` is absent on purpose and is asserted to say so, because a wildcard would be a second spelling
 * of what a bare package import already means.
 */
class QualifiedNameTest {

    /** A `Main` in package `com.github.kusa233.test`, with [imports] and a body of [bodyLines]. */
    private fun program(imports: List<String>, vararg bodyLines: String): SourceFile =
        SourceFile.of(
            "Main.neko",
            buildString {
                append("locate com.github.kusa233.test\n")
                if (imports.isNotEmpty()) {
                    append('\n')
                    for (line in imports) append(line).append('\n')
                }
                append('\n')
                append("class Main {\n")
                append("    static {\n")
                append("        fun main() {\n")
                for (line in bodyLines) append("            ").append(line).append('\n')
                append("        }\n")
                append("    }\n")
                append("}\n")
            },
        )

    private fun messages(imports: List<String>, vararg bodyLines: String): List<String> {
        val result = Compiler.compileProgram(listOf(program(imports, *bodyLines)))
        return TestSupport.errors(result).map { it.message }
    }

    // ------------------------------------------------------------------ importing one class

    @Test
    fun `importing one class makes it reachable by simple name`() {
        assertEquals(
            emptyList(),
            messages(listOf("import java.util.HashMap"), "h: HashMap = HashMap()"),
        )
    }

    @Test
    fun `a singleton-class import and a whole-package import both work`() {
        // The point of comparing them: `import java.util` is the general form, and the single-class
        // form must be no worse at reaching the same class. If these two disagreed, the new syntax
        // would be a special case rather than a narrowing of the old one.
        assertEquals(emptyList(), messages(listOf("import java.util"), "h: HashMap = HashMap()"))
        assertEquals(emptyList(), messages(listOf("import java.util.HashMap"), "h: HashMap = HashMap()"))
    }

    @Test
    fun `a single-class import across two packages is not a duplicate`() {
        // `java.util` and `java.util.HashMap` are different clauses even though one name is a prefix
        // of the other: the first imports a package, the second pins a class. Reporting these as
        // duplicates would make the two forms impossible to combine.
        assertEquals(
            emptyList(),
            messages(listOf("import java.util", "import java.util.HashMap"), "h: HashMap = HashMap()"),
        )
    }

    @Test
    fun `importing the same class twice is still a duplicate`() {
        val result = messages(
            listOf("import java.util.HashMap", "import java.util.HashMap"),
            "h: HashMap = HashMap()",
        )

        assertEquals(listOf("'java.util.HashMap' is already imported"), result)
    }

    @Test
    fun `a class import pins the class it names`() {
        // `java.sql.Date` and `java.util.Date` both exist, and Neko resolves an imported simple name
        // by searching the imports in order. A single-class import is the way to say which one is
        // meant, so it has to answer before the package that would otherwise win.
        assertEquals(
            emptyList(),
            messages(listOf("import java.sql.Date", "import java.util.Date"), "d: Date? = null"),
        )
    }

    @Test
    fun `an import whose target is not a class imports the package`() {
        // The rule the request asked for: when the dotted name is not a class, it is taken as a
        // package. `java.util` is a package, so `HashMap` becomes reachable exactly as it would be
        // with the plain package import — no `.*` needed, and nothing to distinguish the spellings.
        assertEquals(
            emptyList(),
            messages(listOf("import java.util"), "h: HashMap = HashMap()"),
        )
    }

    @Test
    fun `an import of a package the platform does not know is taken on the file's word`() {
        // A class compiled elsewhere cannot be checked, which is the case imports exist for. If an
        // unknown dotted name had to be a class, this program could never have been written.
        assertEquals(
            emptyList(),
            messages(listOf("import com.example.helpers"), "f: Widget? = null"),
        )
    }

    @Test
    fun `the wildcard form is refused and says what to write instead`() {
        val result = messages(listOf("import java.util.*"), "h: HashMap = HashMap()")

        assertEquals(
            listOf(
                "Neko has no '.*': 'import java.util' already imports everything in 'java.util', " +
                    "so write 'import java.util'",
            ),
            result,
        )
    }

    // ------------------------------------------------------------------ qualified types

    @Test
    fun `a qualified name names a type without any import`() {
        assertEquals(
            emptyList(),
            messages(emptyList(), "h: java.util.HashMap = java.util.HashMap()"),
        )
    }

    @Test
    fun `a qualified type in a parameter and in a return type`() {
        val result = Compiler.compileProgram(
            listOf(
                SourceFile.of(
                    "Main.neko",
                    TestSupport.file(
                        "class Main {",
                        "    static {",
                        "        fun build(map: java.util.HashMap): java.util.HashMap {",
                        "            return map",
                        "        }",
                        "    }",
                        "}",
                    ),
                ),
            ),
        )

        assertEquals(emptyList(), TestSupport.errors(result).map { it.message })
    }

    @Test
    fun `a qualified name in a package the JDK does not have is refused`() {
        val result = messages(emptyList(), "h: java.util.Hashmap? = null")

        assertEquals(listOf("'java.util' has no class named 'Hashmap'"), result)
    }

    @Test
    fun `a qualified name whose package is unknown is taken on the file's word`() {
        // The same rule the unqualified fallback has always had: a package the compiler cannot
        // inspect might well hold the class, and refusing it would make qualified names useless for
        // the one case that needs them.
        assertEquals(
            emptyList(),
            messages(emptyList(), "h: com.example.Widget? = null"),
        )
    }

    @Test
    fun `a qualified name ending in a lowercase segment is not a type`() {
        val result = messages(emptyList(), "h: java.util.hashmap? = null")

        assertTrue(
            result.single().startsWith("'java.util.hashmap' does not end in a class name"),
            "the message should point at the missing capital, but was: $result",
        )
    }

    // ------------------------------------------------------------------ qualified expressions

    @Test
    fun `a qualified class can be constructed`() {
        assertEquals(
            emptyList(),
            messages(emptyList(), "h: java.util.HashMap = java.util.HashMap()"),
        )
    }

    @Test
    fun `a static member is called through a qualified name`() {
        assertEquals(
            emptyList(),
            messages(emptyList(), "v: long = java.lang.System.currentTimeMillis()"),
        )
    }

    @Test
    fun `a static field is read through a qualified name`() {
        assertEquals(
            emptyList(),
            messages(emptyList(), "o: Object? = java.lang.System.out"),
        )
    }

    @Test
    fun `a qualified static call runs and returns what the JDK returns`() {
        // A wrong descriptor compiles cleanly and fails at load time, so the value is checked by
        // running the program rather than by looking at its diagnostics.
        val source = SourceFile.of(
            "Main.neko",
            buildString {
                append("locate com.github.kusa233.test\n\n")
                append("class Main {\n    static {\n        fun main() {\n")
                append("            i: int = java.lang.Integer.parseInt(\"7\")\n")
                append("            println(i + 1)\n")
                append("            v: long = java.lang.System.currentTimeMillis()\n")
                append("            println(v > 0)\n")
                append("        }\n    }\n}\n")
            },
        )

        assertEquals(listOf("8", "true"), run(source))
    }

    @Test
    fun `a qualified call to a class that does not exist reports the class`() {
        // The receiver ends in a capital, so it is the class the reader meant; reporting `f` as the
        // unknown name would send them looking for the wrong mistake.
        val result = messages(emptyList(), "v: long = java.lang.Nope.f()")

        assertEquals(listOf("'java.lang' has no class named 'Nope'"), result)
    }

    // ------------------------------------------------------------------ not a regression

    @Test
    fun `an ordinary member chain on a value is still a member chain`() {
        // The whole risk of this feature: `a.b.C` and `value.member` are the same syntax, and only
        // resolution tells them apart. A dotted read of a real value must stay untouched.
        val result = Compiler.compileProgram(
            listOf(
                SourceFile.of(
                    "Main.neko",
                    TestSupport.file(
                        "class Point {",
                        "    val x: int",
                        "    constructor(x: int) {",
                        "        this.x = x",
                        "    }",
                        "}",
                        "",
                        "class Main {",
                        "    static {",
                        "        fun f(p: Point): int {",
                        "            return p.x",
                        "        }",
                        "    }",
                        "}",
                    ),
                ),
            ),
        )

        assertEquals(emptyList(), TestSupport.errors(result).map { it.message })
    }

    @Test
    fun `an enum constant reached through its class is not a qualified type`() {
        // `Code.OK` starts at a class the program declares, so it is a static field read. Treating a
        // chain rooted at a declared class as a package would break every enum in the language.
        val result = Compiler.compileProgram(
            listOf(
                SourceFile.of(
                    "Main.neko",
                    TestSupport.file(
                        "miao class Code {",
                        "    OK,",
                        "    GONE",
                        "}",
                        "",
                        "class Main {",
                        "    static {",
                        "        fun f(): Code {",
                        "            return Code.OK",
                        "        }",
                        "    }",
                        "}",
                    ),
                ),
            ),
        )

        assertEquals(emptyList(), TestSupport.errors(result).map { it.message })
    }

    /** Compiles [source], runs its `main()`, and returns what it printed, one line per entry. */
    private fun run(source: SourceFile): List<String> {
        val directory = Files.createTempDirectory("neko-qualified")
        val result = Compiler.compileProgram(listOf(source), outputDirectory = directory)
        check(!result.hasErrors) {
            "expected the program to compile, but it reported:\n" +
                result.diagnostics.joinToString("\n") { it.toString() }
        }

        val loader = URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader)
        val type = Class.forName("com.github.kusa233.test.Main", true, loader)
        val main = type.declaredMethods.single { it.name == "main" && it.parameterCount == 0 }

        val buffer = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(buffer, true, Charsets.UTF_8))
        try {
            main.invoke(null)
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).replace("\r\n", "\n").trim().lines()
    }
}
