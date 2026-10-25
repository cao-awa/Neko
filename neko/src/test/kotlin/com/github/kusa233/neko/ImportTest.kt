package com.github.kusa233.neko

import com.github.kusa233.neko.platform.PlatformLibrary
import com.github.kusa233.neko.source.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for `import`, and for the standard library needing none.
 *
 * Neko's `import` names a *package*, not one class. The unit of compilation is the whole program, so
 * a class compiled in the same run is already declared and resolves by its simple name from anywhere;
 * what an `import` decides is where a name the program does *not* declare — a class compiled earlier,
 * whose body the compiler never reads — is looked for. A file with no import looks only in its own
 * `locate` package, which is the rule that was there before imports existed.
 *
 * The standard library is the case the language promises to spare the reader: `EarlyEmitException`
 * and `EmitExtractorN` are Neko sources under `stdlib`, compiled alongside the program, so
 * they resolve without an `import` and keep resolving even when one is written.
 */
class ImportTest {

    /** A source in its own package, with the given `import` clause (or none when blank). */
    private fun fileIn(
        packageName: String,
        importLine: String,
        vararg lines: String,
    ): SourceFile = SourceFile.of(
        "${packageName.substringAfterLast('.')}.neko",
        buildString {
            append("locate $packageName\n")
            if (importLine.isNotEmpty()) {
                append('\n')
                append(importLine)
                append('\n')
            }
            append('\n')
            for (line in lines) {
                append(line)
                append('\n')
            }
        },
    )

    /** A `Main` that names `Level`, which nothing in the program declares. */
    private fun namingUndeclaredLevel(importLine: String): SourceFile = fileIn(
        "com.github.kusa233.test",
        importLine,
        "class Main() {",
        "    static {",
        "        fun handle(level: Level): int {",
        "            return 1",
        "        }",
        "    }",
        "}",
    )

    @Test
    fun `an import decides the package of a class the program does not declare`() {
        // `Level` is compiled elsewhere and the compiler never reads its body — which is exactly the
        // case an `import` is for. The import is what makes the generated class file name
        // `com/github/kusa233/other/Level` rather than a `Level` in this file's own package.
        val program = namingUndeclaredLevel("import com.github.kusa233.other")

        val result = TestSupport.compile(program.content, name = "Main.neko")
        assertEquals(
            emptyList(),
            TestSupport.errorLocations(result),
            "a class reached through an import should resolve",
        )
        assertEquals(
            "(Lcom/github/kusa233/other/Level;)I",
            methodDescriptors(result.classes.getValue("com/github/kusa233/test/Main"))["handle"],
            "the import must decide which package the named class belongs to",
        )
    }

    @Test
    fun `without an import an undeclared class belongs to the file's own package`() {
        val program = namingUndeclaredLevel("")

        val result = TestSupport.compile(program.content, name = "Main.neko")
        assertEquals(
            "(Lcom/github/kusa233/test/Level;)I",
            methodDescriptors(result.classes.getValue("com/github/kusa233/test/Main"))["handle"],
            "with no import the name belongs to the file's own package",
        )
    }

    @Test
    fun `an import adds a package without hiding the program's own declarations`() {
        // An import can only ever *add* a way to resolve a name. A class in the file's own package is
        // already known, so `Helper` keeps resolving to it rather than to the same-named class in the
        // imported package.
        val helper = fileIn(
            "com.github.kusa233.other",
            "",
            "class Helper() {",
            "    static {",
            "        fun value(): int {",
            "            return 99",
            "        }",
            "    }",
            "}",
        )
        val program = fileIn(
            "com.github.kusa233.test",
            "import com.github.kusa233.other",
            "class Helper() {",
            "    static {",
            "        fun value(): int {",
            "            return 7",
            "        }",
            "    }",
            "}",
            "",
            "class Main() {",
            "    static {",
            "        fun main() {",
            "            println(Helper.value())",
            "        }",
            "    }",
            "}",
        )

        // Two classes named `Helper` in one program is no longer a redeclaration: they are two
        // different classes, `com.github.kusa233.other.Helper` and `com.github.kusa233.test.Helper`,
        // and the bare name picks the one in this file's own package.
        val loaded = TestSupport.load(listOf(helper, program))
        val printed = loaded.capturingOutput {
            loaded.invoke("com.github.kusa233.test.Main", "main")
        }
        assertEquals("7\n", printed, "the same-package Helper is the one a bare name means")
    }

    @Test
    fun `the standard library needs no import`() {
        // The whole point: a program in a package of its own — not the library's, not the sample's —
        // uses `scratch` and `try` with no `import` at all.
        val program = TestSupport.file(
            "class Main() {",
            "    static {",
            "        fun main() {",
            "            value: int = try Emitter.test() { raised ->",
            "                nyan raised + 1",
            "            }",
            "            println(value)",
            "        }",
            "    }",
            "}",
            "",
            "class Emitter() {",
            "    static {",
            "        fun test(): int {",
            "            v: int = scratch 5",
            "            return v",
            "        } emit [int]",
            "    }",
            "}",
        )

        val result = TestSupport.compileWithLibrary(program)
        assertEquals(
            emptyList(),
            TestSupport.errorLocations(result),
            "the standard library must resolve without an import",
        )
    }

    @Test
    fun `an import does not disturb the standard library`() {
        // Resolution must not become "the imports, and nothing else": an import adds a package a name
        // may come from, and the library's own declarations still answer.
        val program = fileIn(
            "com.github.kusa233.test",
            "import com.github.kusa233.other",
            "class Main() {",
            "    static {",
            "        fun main() {",
            "            value: int = try Emitter.test() { raised ->",
            "                nyan raised",
            "            }",
            "            println(value)",
            "        }",
            "    }",
            "}",
            "",
            "class Emitter() {",
            "    static {",
            "        fun test(): int {",
            "            v: int = scratch 3",
            "            return v",
            "        } emit [int]",
            "    }",
            "}",
        )

        val result = TestSupport.compileWithLibrary(program.content, name = "Main.neko")
        assertEquals(
            emptyList(),
            TestSupport.errorLocations(result),
            "an import must not disturb the library's declarations",
        )
    }

    @Test
    fun `importing the same package twice is an error`() {
        val program = fileIn(
            "com.github.kusa233.test",
            "import com.github.kusa233.other\nimport com.github.kusa233.other",
            "class Main() {",
            "    static {",
            "        fun main() {",
            "        }",
            "    }",
            "}",
        )
        val source = program.content
        val error = TestSupport.errors(TestSupport.compile(source, name = "Main.neko")).single()
        assertEquals("'com.github.kusa233.other' is already imported", error.message)
        assertEquals(
            TestSupport.locationOf(source, "com.github.kusa233.other", occurrence = 2),
            "${error.span?.line}:${error.span?.column}",
            "the error belongs on the second import",
        )
    }

    /**
     * A `Main` that names a platform class the program does not declare, with the given clause.
     *
     * Separate from [namingUndeclaredLevel] because the two cases are answered by different rules:
     * `Level` is a name from a package the compiler cannot see, while `HashMap` is one the platform
     * describes, so the compiler can tell whether the import actually provides it.
     */
    private fun namingHashMap(importLine: String): String = buildString {
        append("locate com.github.kusa233.test\n")
        if (importLine.isNotEmpty()) {
            append('\n')
            append(importLine)
            append('\n')
        }
        append('\n')
        append("class Main() {\n")
        append("    static {\n")
        append("        fun make(other: HashMap): int {\n")
        append("            return 1\n")
        append("        }\n")
        append("    }\n")
        append("}\n")
    }

    @Test
    fun `a java base class resolves through an import of its package`() {
        // `HashMap` is in `java.util`, not `java.lang`, so an unqualified `HashMap` cannot be looked
        // up by the implicit `java.lang` rule. The import is what makes the name resolve — and the
        // descriptor proves it resolved to the right package rather than to the file's own.
        val result = TestSupport.compile(namingHashMap("import java.util"), name = "Main.neko")
        assertEquals(
            emptyList(),
            TestSupport.errorLocations(result),
            "a java.base class should resolve when its package is imported",
        )
        assertEquals(
            "(Ljava/util/HashMap;)I",
            methodDescriptors(result.classes.getValue("com/github/kusa233/test/Main"))["make"],
            "the import must decide the class' real package",
        )
    }

    @Test
    fun `a java base class without its import is reported with the import to write`() {
        // The failure this catches is the common one: `HashMap` written without the import used to
        // compile into the file's own package and fail at run time as `NoClassDefFoundError`. It is
        // now refused, and the diagnostic names the clause rather than only the mistake — which is
        // also what the IDE's quick fix reads.
        val source = namingHashMap("")
        val error = TestSupport.errors(TestSupport.compile(source, name = "Main.neko")).single()

        assertEquals(
            "unknown type 'HashMap': 'HashMap' is java.util.HashMap, which this file does not " +
                "import — write 'import java.util' to reach it",
            error.message,
        )
        assertEquals("java.util", error.suggestedImport, "the fix must be machine-readable too")
        assertEquals(
            TestSupport.locationOf(source, "HashMap"),
            "${error.span?.line}:${error.span?.column}",
            "the error belongs on the name that could not be resolved",
        )
    }

    @Test
    fun `importing a package that does not hold the class is still an error`() {
        // The check is that the class is really in the imported package, not merely that some import
        // exists: `java.util` has no `BufferedReader`, so this must not resolve to
        // `java/util/BufferedReader` just because an import was written.
        val source = namingHashMap("import java.util").replace("HashMap", "BufferedReader")
        val error = TestSupport.errors(TestSupport.compile(source, name = "Main.neko")).single()

        assertTrue(
            error.message.contains("is java.io.BufferedReader"),
            "the message should name the package the class really lives in: ${error.message}",
        )
        assertEquals("java.io", error.suggestedImport)
    }

    @Test
    fun `a java lang class needs no import and suggests none`() {
        // `java.lang` is the implicit first lookup, so `Exception` resolves with no clause at all and
        // must never be reported as needing an import.
        val program = TestSupport.file(
            "class Main() {",
            "    static {",
            "        fun check(failure: Exception) {",
            "        }",
            "    }",
            "}",
        )
        assertEquals(
            emptyList(),
            TestSupport.errorLocations(TestSupport.compile(program)),
            "a java.lang class should resolve without an import",
        )
    }

    @Test
    fun `the platform index knows every package it holds a class in`() {
        // The index is what the analyzer and the IDE both read, so its shape is worth pinning: a
        // name-to-package reverse lookup, and no package offered that holds nothing.
        assertTrue(
            PlatformLibrary.hasPackage("java.util"),
            "java.util should be indexed",
        )
        assertEquals(
            listOf("java.util"),
            PlatformLibrary.packagesOf("HashMap"),
            "HashMap lives in exactly one package",
        )
        assertEquals(
            "java.io",
            PlatformLibrary.importPackageFor("BufferedReader"),
            "the reverse lookup is what an automatic import is built from",
        )
        assertNull(
            PlatformLibrary.importPackageFor("Exception"),
            "a java.lang class needs no import, so there is nothing to insert",
        )
        assertNull(
            PlatformLibrary.importPackageFor("Level"),
            "a name the platform does not have cannot suggest a package",
        )
    }

    /** The parameter/return descriptor of every method in [bytes], keyed by name. */
    private fun methodDescriptors(bytes: ByteArray): Map<String, String> {
        val methods = mutableMapOf<String, String>()
        org.objectweb.asm.ClassReader(bytes).accept(
            object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    thrown: Array<out String>?,
                ): org.objectweb.asm.MethodVisitor? {
                    methods[name] = descriptor
                    return null
                }
            },
            org.objectweb.asm.ClassReader.SKIP_CODE,
        )
        return methods
    }
}
