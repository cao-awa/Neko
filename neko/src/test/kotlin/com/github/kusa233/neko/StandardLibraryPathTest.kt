package com.github.kusa233.neko

import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.source.StandardLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--stdlib-path`, and an explicitly supplied standard library in general.
 *
 * The library is normally the copy the compiler carries, compiled ahead of every program without the
 * caller asking. This option is the escape hatch: someone working on the library itself, or pinning a
 * version of it for a project, needs the compiler to read theirs instead.
 *
 * What is worth testing is not that the option parses — that is a string comparison — but that the
 * supplied library is genuinely *used* and the packaged one is genuinely *not*. A silent fallback is
 * the failure mode that matters: it would make a mistyped path look like a working build, and every
 * program would compile against a library the reader did not choose.
 */
class StandardLibraryPathTest {

    /** A program in package `demo` that names [className] without declaring it. */
    private fun programNaming(className: String): SourceFile = SourceFile.of(
        "Uses.neko",
        buildString {
            append("locate demo\n\n")
            append("class Uses {\n")
            append("    static {\n")
            append("        fun f(): $className {\n")
            append("            return $className()\n")
            append("        }\n")
            append("    }\n")
            append("}\n")
        },
    )

    /** Writes [files] as `relative/path.neko` under a fresh directory. */
    private fun libraryOf(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("neko-stdlib")
        for ((relative, text) in files) {
            val target = root.resolve(relative)
            target.parent?.createDirectories()
            target.writeText(text)
        }
        return root
    }

    @Test
    fun `a supplied library is compiled ahead of the program`() {
        // `Marker` exists nowhere but the supplied library, so the program compiles only if the
        // library really did come from there.
        val library = libraryOf(
            "custom/Marker.neko" to "locate custom\n\nclass Marker() {\n}\n",
        )

        val result = Compiler.compileProgram(
            listOf(programNaming("Marker")),
            library = StandardLibrary.sourcesIn(library),
        )

        assertEquals(emptyList(), TestSupport.errors(result).map { it.message })
    }

    @Test
    fun `a supplied library replaces the packaged one rather than adding to it`() {
        // The packaged library declares `EmitYieldException`, which is what `scratch` needs. If the
        // supplied library were *added* to the packaged one instead of replacing it, this would
        // compile — and a reader pinning an older library would silently get the new one's classes.
        val library = libraryOf(
            "custom/Marker.neko" to "locate custom\n\nclass Marker() {\n}\n",
        )

        val result = Compiler.compileProgram(
            listOf(
                SourceFile.of(
                    "Uses.neko",
                    buildString {
                        append("locate demo\n\n")
                        append("class Uses {\n    static {\n")
                        append("        fun f(): int {\n            return scratch 1\n        }\n")
                        append("    }\n}\n")
                    },
                ),
            ),
            library = StandardLibrary.sourcesIn(library),
        )

        assertTrue(
            TestSupport.errors(result).any { "EmitYieldException" in it.message },
            "the packaged library should not have supplied EmitYieldException, but the program " +
                "compiled against it anyway",
        )
    }

    @Test
    fun `an empty library is honoured and not replaced`() {
        // "I have my own and it is empty" and "I did not say" are different statements. Substituting
        // the packaged library for the former is what would make a broken path look healthy.
        val library = libraryOf()

        val result = Compiler.compileProgram(
            listOf(
                SourceFile.of(
                    "Uses.neko",
                    buildString {
                        append("locate demo\n\n")
                        append("class Uses {\n    static {\n")
                        append("        fun f(): int {\n            return scratch 1\n        }\n")
                        append("    }\n}\n")
                    },
                ),
            ),
            library = StandardLibrary.sourcesIn(library),
        )

        assertTrue(
            TestSupport.errors(result).any { "EmitYieldException" in it.message },
            "an empty library should leave the program without EmitYieldException",
        )
    }

    @Test
    fun `the packaged library is used when no path is given`() {
        // The default has to keep working, or every existing caller breaks.
        val result = Compiler.compileProgram(
            listOf(
                SourceFile.of(
                    "Uses.neko",
                    buildString {
                        append("locate demo\n\n")
                        append("class Uses {\n    static {\n")
                        append("        fun f(): int {\n            return scratch 1\n        }\n")
                        append("    }\n}\n")
                    },
                ),
            ),
        )

        // `scratch` is only legal in a function declaring an emit clause; what matters here is that
        // the failure is *not* about a missing library.
        assertTrue(
            TestSupport.errors(result).none { "EmitYieldException" in it.message },
            "the packaged library should have supplied EmitYieldException",
        )
    }

    @Test
    fun `a library directory is walked recursively`() {
        // Packages are folders, so a library that could only be read flat would be useless for
        // anything but a toy. The nested declaration is the only one here, so finding it proves the
        // walk descended.
        val library = libraryOf(
            "com/example/deep/Nested.neko" to "locate com.example.deep\n\nclass Nested() {\n}\n",
        )

        val result = Compiler.compileProgram(
            listOf(programNaming("Nested")),
            library = StandardLibrary.sourcesIn(library),
        )

        assertEquals(emptyList(), TestSupport.errors(result).map { it.message })
    }

    @Test
    fun `a supplied library keeps the path it was read from`() {
        // A library file on disk really exists, so a diagnostic about it can name the file to open.
        // The packaged copy has no path because there is no file behind it; the two cases differ.
        val library = libraryOf(
            "custom/Marker.neko" to "locate custom\n\nclass Marker() {\n}\n",
        )

        val sources = StandardLibrary.sourcesIn(library)

        assertEquals(1, sources.size)
        assertTrue(sources.single().path != null, "a file read from disk should keep its path")
    }

    @Test
    fun `a missing directory yields no sources and reports nothing`() {
        // This function is also used to probe, so it must not throw; the caller decides whether an
        // empty result is a mistake, because only it knows whether a library was expected.
        val missing = Files.createTempDirectory("neko-stdlib").resolve("not-there")

        assertEquals(emptyList(), StandardLibrary.sourcesIn(missing))
    }
}
