package com.github.kusa233.neko

import com.github.kusa233.neko.diagnostics.Diagnostic
import com.github.kusa233.neko.diagnostics.Severity
import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.source.StandardLibrary
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared helpers for the compiler tests.
 *
 * Compilation is exercised as a whole wherever possible: the value of a compiler is that source text
 * goes in and something that runs comes out, so most tests assert on the class files or on what one
 * of them prints rather than on an intermediate tree.
 */
object TestSupport {

    /** Compiles [source] in memory, without writing anything to disk. */
    fun compile(source: String, name: String = "Test.neko"): CompilationResult =
        Compiler.compile(SourceFile.of(name, source), outputDirectory = null)

    /**
     * Compiles [source] together with the Neko standard library, in memory.
     *
     * A program that uses `raise` or `try` is lowered against the library's `EarlyEmitException` and
     * `EmitExtractor`, so without those declarations the compiler reports that the library is missing
     * — which is right for a program meant to run, and in the way for a test that only cares about
     * some other rule in a program that happens to raise. Those tests compile it this way instead.
     */
    fun compileWithLibrary(source: String, name: String = "Test.neko"): CompilationResult =
        Compiler.compileProgram(
            standardLibrarySources() + SourceFile.of(name, source),
            outputDirectory = null,
        )

    /** The messages of every diagnostic, in production order. */
    fun messages(result: CompilationResult): List<String> = result.diagnostics.map { it.message }

    /** Only the errors, so a test can ignore warnings it does not care about. */
    fun errors(result: CompilationResult): List<Diagnostic> =
        result.diagnostics.filter { it.severity == Severity.ERROR }

    /**
     * Every error rendered as `file:line:column: message`, which is the form a user reads.
     *
     * Asserting on this rather than on bare messages pins down *where* the compiler pointed, which is
     * half of what makes a diagnostic useful.
     */
    fun errorLocations(result: CompilationResult): List<String> =
        errors(result).map { diagnostic ->
            val span = diagnostic.span
            val location = if (span == null) "?" else "${span.file.name}:${span.line}:${span.column}"
            "$location: ${diagnostic.message}"
        }

    /** Asserts that [source] compiles without errors and returns the result. */
    fun compileSuccessfully(source: String, name: String = "Test.neko"): CompilationResult {
        val result = compile(source, name)
        val rendered = result.diagnostics.joinToString("\n") { it.toString() }
        check(!result.hasErrors) { "expected $name to compile, but it reported:\n$rendered" }
        return result
    }

    /**
     * Compiles [source] into a fresh directory and loads the result.
     *
     * The classes are written to a real directory rather than defined from memory because the
     * generated code refers to itself and to JDK types by name; a normal class loader resolving
     * those names off disk is exactly what the JVM will do with the compiler's real output.
     */
    fun load(source: String, name: String = "Test.neko"): LoadedProgram =
        load(listOf(SourceFile.of(name, source)))

    /**
     * Compiles several [sources] as one program and loads the result.
     *
     * The unit of compilation is a program, not a file, so a test whose program names the standard
     * library has to hand both to the compiler at once — exactly as the build does when it compiles
     * a whole source directory.
     */
    fun load(sources: List<SourceFile>): LoadedProgram {
        val directory = Files.createTempDirectory("neko-test")
        val result = Compiler.compileProgram(sources, outputDirectory = directory)
        check(!result.hasErrors) {
            "expected the program to compile, but it reported:\n" +
                result.diagnostics.joinToString("\n") { it.toString() }
        }

        val loader = URLClassLoader(arrayOf(directory.toUri().toURL()), TestSupport::class.java.classLoader)
        return LoadedProgram(result, directory, loader)
    }

    /**
     * Compiles [source] the same way a program is compiled.
     *
     * The standard library is no longer something a test adds: the compiler compiles its own copy
     * ahead of every program, so an ordinary [compile] or [load] already has it. This is kept as a
     * named entry point so a test that reads oddly without an explanation can say *why* it takes the
     * ordinary route — and so that if the library ever needs threading in per-test again, there is
     * one place to do it rather than sixteen call sites.
     */
    fun loadWithLibrary(source: String, name: String = "Test.neko"): LoadedProgram =
        load(source, name)

    /** The library the compiler compiles ahead of every program, for tests that assert on it. */
    fun standardLibrarySources(): List<SourceFile> = StandardLibrary.sources()

    /**
     * The JVM internal name of `EmitYieldException`, as the compiler actually emitted it.
     *
     * Read from the library's own declaration rather than written out as a literal, so a test asserting
     * on an `Exceptions` attribute cannot go stale when the library moves: the name is the library's
     * package plus the simple name, and both are already known here.
     */
    fun emitYieldExceptionClass(): String {
        val declaration = standardLibrarySources().firstOrNull {
            emitYieldExceptionSimpleName in it.content
        } ?: error("the standard library declares no $emitYieldExceptionSimpleName")
        // The package comes from the file's own `locate`, which is what the compiler emits against.
        val packageName = declaration.content.lineSequence()
            .first { it.trimStart().startsWith("locate ") }
            .trim()
            .removePrefix("locate ")
            .trim()
        return packageName.replace('.', '/') + "/$emitYieldExceptionSimpleName"
    }

    private const val emitYieldExceptionSimpleName = "EmitYieldException"

    /** A compiled program, loaded and ready to be invoked. */
    class LoadedProgram(
        val result: CompilationResult,
        val directory: Path,
        private val loader: URLClassLoader,
    ) {
        /** Loads a generated class, so a test can inspect its real signature. */
        fun typeOf(typeName: String): Class<*> = Class.forName(typeName, false, loader)

        /** Invokes a static method with no parameters and no result, e.g. `Sample.main`. */
        fun invoke(typeName: String, methodName: String, vararg arguments: Any?): Any? {
            val type = typeOf(typeName)
            val method = type.methods.single { candidate ->
                candidate.name == methodName &&
                    candidate.parameterCount == arguments.size &&
                    candidate.parameterTypes.withIndex().all { (index, parameterType) ->
                        parameterType.isInstance(arguments[index]) ||
                            isPrimitiveMatch(parameterType, arguments[index])
                    }
            }
            return try {
                method.invoke(null, *arguments)
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                throw failure.targetException
            }
        }

        /** Captures everything the program writes to standard output while [body] runs. */
        fun capturingOutput(body: () -> Unit): String {
            val original = System.out
            val buffer = ByteArrayOutputStream()
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            try {
                body()
            } finally {
                System.setOut(original)
            }
            return buffer.toString(Charsets.UTF_8).replace("\r\n", "\n")
        }

        /**
         * A no-op, kept so a caller that used to compile Java support classes still reads as
         * "everything this program needs is present".
         *
         * The standard library is Neko now and is compiled *with* the program by
         * [TestSupport.loadWithLibrary], so there is nothing left to supply afterwards. The JVM
         * resolves a class' supertypes when it loads it, which is why this used to be necessary.
         */
        fun compileSupportClasses() = Unit

        private fun isPrimitiveMatch(parameterType: Class<*>, argument: Any?): Boolean = when (parameterType) {
            java.lang.Integer.TYPE -> argument is Int
            java.lang.Boolean.TYPE -> argument is Boolean
            else -> false
        }
    }

    /**
     * Builds a program in package `com.github.kusa233.test` whose class is `Test`.
     *
     * The body is given one line per argument, verbatim, so a test can write the exact text it
     * wants compiled. String templates would be shorter, but dedenting a multi-line template only
     * indents its first line — which silently shifts every position a diagnostic test asserts on.
     */
    fun program(vararg bodyLines: String): String = buildString {
        append("locate com.github.kusa233.test\n")
        append('\n')
        append("class Test {\n")
        for (line in bodyLines) {
            append(line)
            append('\n')
        }
        append("}\n")
    }

    /**
     * Builds a program in package `com.github.kusa233.test` from whole declarations, verbatim.
     *
     * [program] wraps its lines in a single class, which is the right shape for a rule about
     * statements. A rule about *classes* — how members are declared, or how one class reaches
     * another — needs to write the classes itself, so this emits the package line and then the given
     * text unchanged.
     */
    fun file(vararg lines: String): String = buildString {
        append("locate com.github.kusa233.test\n")
        append('\n')
        for (line in lines) {
            append(line)
            append('\n')
        }
    }

    /**
     * The 1-based `line:column` at which [needle] appears in [source].
     *
     * Diagnostic positions are asserted through this rather than as literal numbers: the numbers
     * then come from the same text the compiler saw, so editing a test's source can never leave a
     * stale column behind.
     *
     * A [needle] that is a bare identifier is matched as a whole word, so searching for `str` does
     * not trip over `str` inside `string` — an easy way to write a test that passes for the wrong
     * reason.
     */
    fun locationOf(source: String, needle: String, occurrence: Int = 1): String {
        val pattern = if (needle.all { it.isLetterOrDigit() || it == '_' }) {
            Regex("\\b${Regex.escape(needle)}\\b")
        } else {
            Regex(Regex.escape(needle))
        }
        var remaining = occurrence
        source.lineSequence().forEachIndexed { index, text ->
            for (match in pattern.findAll(text)) {
                remaining--
                if (remaining == 0) return "${index + 1}:${match.range.first + 1}"
            }
        }
        throw AssertionError("'$needle' does not appear in:\n$source")
    }

    /**
     * The relative output path the build passes to the compiler.
     *
     * It is relative on purpose: the classes land inside the project directory rather than at the
     * root of whichever drive the build happens to run on.
     */
    const val OUTPUT_DIRECTORY = "neko-compile/classes"

    /**
     * The *root* project directory, found by walking up from the working directory.
     *
     * The marker is `settings.gradle.kts` rather than `build.gradle.kts`, because this module has a
     * build file of its own: stopping at the first one would stop at `neko/`, and `samples/` — which
     * the samples tests read and [OUTPUT_DIRECTORY] is relative to — lives at the root, one level
     * further up. Only the settings file marks where the build as a whole begins.
     */
    val projectDirectory: Path = run {
        var candidate = Path.of("").toAbsolutePath()
        while (!Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.parent
                ?: error("cannot find settings.gradle.kts above ${Path.of("").toAbsolutePath()}")
        }
        candidate
    }
}
