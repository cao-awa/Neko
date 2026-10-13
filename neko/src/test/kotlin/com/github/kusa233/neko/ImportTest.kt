package com.github.kusa233.neko

import com.github.kusa233.neko.source.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

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
        "    static fun handle(level: Level): int {",
        "        return 1",
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
        // An import can only ever *add* a way to resolve a name. A class the program declares is
        // already known, so `Helper` keeps resolving to the declaration rather than to a same-named
        // class in the imported package.
        val helper = fileIn(
            "com.github.kusa233.other",
            "",
            "class Helper() {",
            "    static fun value(): int {",
            "        return 99",
            "    }",
            "}",
        )
        val program = fileIn(
            "com.github.kusa233.test",
            "import com.github.kusa233.other",
            "class Helper() {",
            "    static fun value(): int {",
            "        return 7",
            "    }",
            "}",
            "",
            "class Main() {",
            "    static fun main() {",
            "        println(Helper.value())",
            "    }",
            "}",
        )

        // Two classes named `Helper` in one program is a redeclaration, whichever packages they are
        // in: a class is known by its simple name across the whole program.
        val result = Compiler.compileProgram(listOf(helper, program), outputDirectory = null)
        val messages = TestSupport.messages(result)
        assertEquals(
            listOf("class 'Helper' is already declared"),
            messages.filter { it.contains("Helper") },
            "a declared name is program-wide, so the import cannot pick the other Helper",
        )
    }

    @Test
    fun `the standard library needs no import`() {
        // The whole point: a program in a package of its own — not the library's, not the sample's —
        // uses `raise` and `try` with no `import` at all.
        val program = TestSupport.file(
            "class Main() {",
            "    static fun main() {",
            "        value: int = try Emitter.test() { raised ->",
            "            yield raised + 1",
            "        }",
            "        println(value)",
            "    }",
            "}",
            "",
            "class Emitter() {",
            "    static fun test(): int {",
            "        raise 5",
            "    } emit [int]",
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
            "    static fun main() {",
            "        value: int = try Emitter.test() { raised ->",
            "            yield raised",
            "        }",
            "        println(value)",
            "    }",
            "}",
            "",
            "class Emitter() {",
            "    static fun test(): int {",
            "        raise 3",
            "    } emit [int]",
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
            "    static fun main() {",
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
