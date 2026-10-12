package com.github.kusa233.neko

import com.github.kusa233.neko.analyzer.Analyzer
import com.github.kusa233.neko.ast.NekoFile
import com.github.kusa233.neko.ast.NekoProgram
import com.github.kusa233.neko.codegen.BytecodeGenerator
import com.github.kusa233.neko.diagnostics.Diagnostic
import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.diagnostics.DiagnosticRenderer
import com.github.kusa233.neko.diagnostics.Severity
import com.github.kusa233.neko.parser.Parser
import com.github.kusa233.neko.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** The outcome of compiling a program. */
class CompilationResult(
    /** The parsed files, in the order they were given. Empty when nothing parsed at all. */
    val files: List<NekoFile>,
    /** Every diagnostic from every pass, in source order of production. */
    val diagnostics: List<Diagnostic>,
    /** Class files produced, keyed by JVM internal name such as `com/example/Sample`. */
    val classes: Map<String, ByteArray>,
    /** Where the class files were written, or `null` when nothing was emitted. */
    val outputDirectory: Path?,
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }

    /** The first parsed file, kept for callers that compiled exactly one. */
    val file: NekoFile? get() = files.firstOrNull()

    /** Diagnostics rendered in the compiler's source-oriented format, one entry per message. */
    fun renderDiagnostics(useColor: Boolean = false): List<String> =
        diagnostics.map { DiagnosticRenderer.render(it, useColor) }
}

/**
 * The Neko compiler: source text in, `.class` files out.
 *
 * The pipeline is the conventional one, and each stage reports into a single [DiagnosticBag] so one
 * run surfaces as many independent problems as possible:
 *
 * ```
 * source text → Lexer → Parser → Analyzer → BytecodeGenerator → .class files
 * ```
 *
 * **The unit of compilation is a program, not a file.** Neko programs are split across files — the
 * standard library that `raise` depends on is itself written in Neko — so all of them are parsed
 * and then analysed against *one* symbol table. That is what lets a class in one file name a class
 * declared in another instead of being treated as an unknown type. Code generation only runs when
 * analysis found no errors, so a program that fails to compile never leaves stale or half-written
 * class files behind.
 */
object Compiler {

    /** The extension an Neko source file is expected to carry. */
    const val SOURCE_EXTENSION = "neko"

    /** Compiles one [source] as a program of its own. */
    fun compile(source: SourceFile, outputDirectory: Path? = null): CompilationResult =
        compileProgram(listOf(source), outputDirectory)

    /** Compiles the file at [path] to class files under [outputDirectory]. */
    fun compileFile(path: Path, outputDirectory: Path? = null): CompilationResult =
        compileProgram(listOf(readSource(path)), outputDirectory)

    /**
     * Compiles every `.neko` file under [directory], at any depth.
     *
     * Only regular files with the [SOURCE_EXTENSION] are taken, and they are sorted by path so a run
     * is reproducible: a build must not depend on the order a directory happens to enumerate in.
     * Recursion is what lets one configured directory hold both a program and the standard library
     * it names, each in its own package folder.
     */
    fun compileDirectory(directory: Path, outputDirectory: Path? = null): CompilationResult {
        val sources = Files.walk(directory).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == SOURCE_EXTENSION }
                .sorted()
                .map { readSource(it) }
                .toList()
        }
        return compileProgram(sources, outputDirectory)
    }

    /**
     * Compiles several files that name each other.
     *
     * Every file is parsed first, and analysis only starts when all of them parsed, so a syntax error
     * in the standard library is reported before a program's own type errors — which is the order a
     * reader needs, because until the library parses nothing it declares can be resolved.
     */
    fun compileProgram(sources: List<SourceFile>, outputDirectory: Path? = null): CompilationResult {
        if (sources.isEmpty()) {
            val diagnostics = DiagnosticBag()
            diagnostics.error("there are no .neko files to compile", null)
            return CompilationResult(emptyList(), diagnostics.diagnostics, emptyMap(), null)
        }

        val diagnostics = DiagnosticBag()
        val files = sources.map { Parser(it, diagnostics).parseFile() }

        if (diagnostics.hasErrors) {
            // Parsing already failed; analysing a partial tree would only add noise.
            return CompilationResult(files, diagnostics.diagnostics, emptyMap(), null)
        }

        val program = NekoProgram(files)
        val analyzer = Analyzer(program, diagnostics)
        val classes = analyzer.analyze()

        if (diagnostics.hasErrors) {
            return CompilationResult(files, diagnostics.diagnostics, emptyMap(), null)
        }

        val generated = BytecodeGenerator(
            program = program,
            classes = classes,
            parametersByFunction = analyzer.allParameters(),
            diagnostics = diagnostics,
        ).generate()

        if (diagnostics.hasErrors) {
            return CompilationResult(files, diagnostics.diagnostics, emptyMap(), null)
        }

        val written = outputDirectory?.let { writeClasses(it, generated) }
        return CompilationResult(files, diagnostics.diagnostics, generated, written)
    }

    private fun readSource(path: Path): SourceFile = SourceFile(
        name = path.fileName?.toString() ?: path.name,
        content = Files.readString(path),
        path = path,
    )

    /** Writes each class file, creating the package directories as needed. */
    private fun writeClasses(outputDirectory: Path, classes: Map<String, ByteArray>): Path {
        outputDirectory.createDirectories()
        for ((internalName, bytes) in classes) {
            val target = outputDirectory.resolve("$internalName.class")
            target.parent?.createDirectories()
            Files.write(target, bytes)
        }
        return outputDirectory
    }

    /**
     * Renders diagnostics for the terminal, in source order.
     *
     * Diagnostics are ordered by position so that reading the output top-to-bottom matches reading
     * the source top-to-bottom. A diagnostic without a location (an internal error, say) sorts last.
     */
    fun renderAll(diagnostics: List<Diagnostic>, useColor: Boolean = false): String =
        diagnostics.sortedBy { it.span?.startPosition?.offset ?: Int.MAX_VALUE }
            .joinToString("\n\n") { DiagnosticRenderer.render(it, useColor) }
}
