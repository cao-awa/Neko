package com.github.kusa233.neko

import com.github.kusa233.neko.source.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `locate` decides which names a file may write without saying where they come from.
 *
 * Before this rule, `locate` only decided where a file *emitted* its classes. Naming was global: a
 * name resolved to whatever the program declared anywhere, so a file in `demo.b` could write
 * `Helper`, mean the one in `demo.a`, and never mention the package. `import` was therefore
 * decoration — there was no name it could make reachable that was not already reachable.
 *
 * Now the two halves agree, the way they do in Java: two files that declare the same `locate` are one
 * package and see each other freely; a file in another package has to import, and the diagnostic says
 * exactly which line to write. Qualified names (`demo.a.Helper`) remain usable without an import,
 * because spelling out the package *is* saying where the name comes from.
 *
 * These tests are grouped by the surface the name appears on rather than by feature, because the
 * interesting failure mode is asymmetry: a type position honouring the rule while a static member
 * access quietly does not would let a program walk around the rule by writing `Helper.shared` instead
 * of `Helper`. Each surface is therefore checked for the refusal *and* for the import that fixes it.
 */
class PackageVisibilityTest {

    // ---------------------------------------------------------------- helpers

    /** A file that declares `Helper` in [packageName], with a static member of each shape. */
    private fun helperIn(packageName: String): SourceFile = SourceFile.of(
        "Helper.neko",
        buildString {
            append("locate $packageName\n\n")
            append("class Helper {\n")
            append("    static {\n")
            append("        var shared: int = 42\n")
            append("        fun util(x: int): int { return x * 2 }\n")
            append("    }\n")
            append("}\n")
        },
    )

    /** A file in [packageName], optionally importing [imports], whose `main` is [body]. */
    private fun mainIn(packageName: String, vararg imports: String, body: String): SourceFile =
        SourceFile.of(
            "Main.neko",
            buildString {
                append("locate $packageName\n")
                for (clause in imports) append("import $clause\n")
                append("\nclass Main {\n")
                append("    static {\n")
                append("        fun main() {\n")
                for (line in body.trimIndent().lines()) append("            $line\n")
                append("        }\n")
                append("    }\n")
                append("}\n")
            },
        )

    /** Compiles [sources] as one program, without loading anything. */
    private fun compileAll(vararg sources: SourceFile) =
        Compiler.compileProgram(sources.toList(), outputDirectory = null)

    /** The error messages of [sources] compiled as one program. */
    private fun messagesOf(vararg sources: SourceFile): List<String> =
        TestSupport.errors(compileAll(*sources)).map { it.message }

    // ------------------------------------------------- same package needs nothing

    @Test
    fun `a class in the same package is nameable with no import`() {
        val errors = messagesOf(
            helperIn("demo.a"),
            mainIn("demo.a", body = "h: Helper = Helper()\nprintln(h.shared)"),
        )
        assertEquals(emptyList(), errors, "the same package should need no import")
    }

    @Test
    fun `two files with no locate at all are one package`() {
        // The unnamed package is a package: a directory of `locate`-less files is the simplest
        // program there is, and demanding imports between them would make it unusable.
        val helper = SourceFile.of(
            "Helper.neko",
            "class Helper {\n    static {\n        var shared: int = 1\n    }\n}\n",
        )
        val main = SourceFile.of(
            "Main.neko",
            "class Main {\n    static {\n        fun main() {\n            println(Helper.shared)\n        }\n    }\n}\n",
        )
        assertEquals(emptyList(), messagesOf(helper, main))
    }

    // ------------------------------------------------- other packages need an import

    @Test
    fun `a class in another package is refused without an import`() {
        val messages = messagesOf(
            helperIn("demo.a"),
            mainIn("demo.b", body = "h: Helper = Helper()\nprintln(h.shared)"),
        )
        assertTrue(
            messages.any { "another package" in it && "import demo.a.Helper" in it },
            "expected the import advice, but got:\n${messages.joinToString("\n")}",
        )
    }

    @Test
    fun `importing the class makes it nameable`() {
        val errors = messagesOf(
            helperIn("demo.a"),
            mainIn("demo.b", "demo.a.Helper", body = "h: Helper = Helper()\nprintln(h.shared)"),
        )
        assertEquals(emptyList(), errors, "an import of the class should be enough")
    }

    @Test
    fun `importing the package makes it nameable`() {
        val errors = messagesOf(
            helperIn("demo.a"),
            mainIn("demo.b", "demo.a", body = "h: Helper = Helper()\nprintln(h.shared)"),
        )
        assertEquals(emptyList(), errors, "an import of the package should be enough")
    }

    @Test
    fun `a qualified name needs no import`() {
        // Spelling the package out *is* saying where the name comes from, so the rule has nothing
        // left to ask for. This is also what keeps the JDK's `java.util.HashMap` usable.
        val errors = messagesOf(
            helperIn("demo.a"),
            mainIn("demo.b", body = "println(demo.a.Helper.shared)"),
        )
        assertEquals(emptyList(), errors, "a qualified name should need no import")
    }

    // ------------------------------------------------ the same rule on every surface

    @Test
    fun `a static field of another package's class is refused without an import`() {
        // The asymmetry this guards against: a type position honouring the rule while a member
        // access does not would let a program reach the class by writing `Helper.shared`.
        val messages = messagesOf(
            helperIn("demo.a"),
            mainIn("demo.b", body = "println(Helper.shared)"),
        )
        assertTrue(
            messages.any { "another package" in it && "import demo.a.Helper" in it },
            "expected the import advice, but got:\n${messages.joinToString("\n")}",
        )
    }

    @Test
    fun `a static call of another package's class is refused without an import`() {
        val messages = messagesOf(
            helperIn("demo.a"),
            mainIn("demo.b", body = "println(Helper.util(2))"),
        )
        assertTrue(
            messages.any { "another package" in it && "import demo.a.Helper" in it },
            "expected the import advice, but got:\n${messages.joinToString("\n")}",
        )
    }

    @Test
    fun `extending another package's class is refused without an import`() {
        val messages = messagesOf(
            SourceFile.of("Base.neko", "locate demo.a\n\nclass Base() {\n}\n"),
            SourceFile.of(
                "Main.neko",
                "locate demo.b\n\nclass Main: Base {\n    static {\n        fun main() {\n            println(1)\n        }\n    }\n}\n",
            ),
        )
        assertTrue(
            messages.any { "another package" in it && "import demo.a.Base" in it },
            "expected the import advice, but got:\n${messages.joinToString("\n")}",
        )
    }

    @Test
    fun `a static member reachable with an import still works at run time`() {
        // The refusal is only half the story: the same program with the import has to *run*, which
        // proves the rule narrowed visibility rather than breaking the resolution behind it.
        val loaded = TestSupport.load(
            listOf(
                helperIn("demo.a"),
                mainIn("demo.b", "demo.a.Helper", body = "println(Helper.shared)\nprintln(Helper.util(2))"),
            ),
        )
        val printed = loaded.capturingOutput {
            loaded.invoke("demo.b.Main", "main")
        }
        assertEquals(listOf("42", "4"), printed.trim().lines())
    }

    // ------------------------------------------------ what must not have changed

    @Test
    fun `the standard library still needs no import for scratch to work`() {
        // `scratch`, `nyan` and `yield` are lowered against `EmitYieldException`, which the library
        // declares in its own package. If the new rule applied to that internal reference, every
        // program using the three keywords would break — so it is worth pinning down that it does not.
        val result = TestSupport.compileWithLibrary(
            buildString {
                append("locate com.github.kusa233.test\n\n")
                append("class Test {\n")
                append("    static {\n")
                append("        fun risky(): int { return 1 / 0 }\n")
                append("        fun guard(): int {\n")
                append("            v: int = try risky() scratch 5\n")
                append("            return v\n")
                append("        } emit [int]\n")
                append("        fun main() {\n")
                append("            r: int = try guard() { x -> nyan x }\n")
                append("            println(r)\n")
                append("        }\n")
                append("    }\n")
                append("}\n")
            },
        )
        assertEquals(emptyList(), TestSupport.errors(result).map { it.message })
    }

    @Test
    fun `a program may declare a class whose name another package also declares`() {
        // Duplicate simple names across packages stay an error, and the package rule does not change
        // that: the lookup is still one map by simple name, now with a permission check on top.
        val messages = messagesOf(
            SourceFile.of("A.neko", "locate demo.a\n\nclass Thing() {\n}\n"),
            SourceFile.of("B.neko", "locate demo.b\n\nclass Thing() {\n}\n"),
        )
        assertTrue(
            messages.any { "already declared" in it },
            "expected the duplicate-declaration error, but got:\n${messages.joinToString("\n")}",
        )
    }
}
