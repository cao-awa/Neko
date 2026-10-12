package com.github.kusa233.neko

import com.github.kusa233.neko.diagnostics.RenderStyle
import com.github.kusa233.neko.diagnostics.Severity
import com.github.kusa233.neko.source.SourceFile
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Command-line entry point of the Neko compiler.
 *
 * ```
 * neko <file-or-directory>... [--output <directory>]
 * ```
 *
 * Each argument may be a `.neko` file or a directory of them; a directory contributes every `.neko`
 * file directly inside it. Several may be given because an Neko program is compiled as a whole —
 * a program and the standard library it names have to see each other's declarations.
 *
 * On success the compiled class files are written and `0` is returned; when the program fails to
 * compile every diagnostic is printed and the exit status is `1`, so the compiler composes with
 * ordinary build tooling. `2` is reserved for an internal compiler error, which must never be
 * mistaken for a problem with the program being compiled.
 */
fun main(args: Array<String>) {
    val options = try {
        CommandLine.parse(args)
    } catch (failure: CommandLineException) {
        System.err.println("neko: ${failure.message}")
        System.err.println()
        System.err.println(USAGE)
        kotlin.system.exitProcess(1)
    }

    if (options == null) {
        println(USAGE)
        return
    }

    val sources = mutableListOf<SourceFile>()
    for (path in options.sources) {
        if (!path.exists()) {
            System.err.println("neko: no such file or directory: $path")
            kotlin.system.exitProcess(1)
        }
        if (path.isDirectory()) {
            val found = try {
                collectSources(path)
            } catch (failure: Exception) {
                System.err.println("neko: cannot read the directory $path: ${failure.message}")
                kotlin.system.exitProcess(1)
            }
            if (found.isEmpty()) {
                System.err.println("neko: no .${Compiler.SOURCE_EXTENSION} files in $path")
                kotlin.system.exitProcess(1)
            }
            sources += found
        } else {
            sources += readSource(path)
        }
    }

    val summary = if (options.sources.size == 1) options.sources.single().toString() else {
        "${options.sources.size} paths"
    }

    val result = try {
        Compiler.compileProgram(sources, options.outputDirectory)
    } catch (failure: Exception) {
        // An internal failure must never look like a program error, so the trace is shown in full.
        System.err.println("neko: internal error while compiling $summary")
        failure.printStackTrace()
        kotlin.system.exitProcess(2)
    }

    if (result.diagnostics.isNotEmpty()) {
        val text = Compiler.renderAll(result.diagnostics, useColor = options.useColor)
        val stream = if (result.hasErrors) System.err else System.out
        stream.println(text)
        stream.println()
    }

    val errorCount = result.diagnostics.count { it.severity == Severity.ERROR }
    val warningCount = result.diagnostics.count { it.severity == Severity.WARNING }

    if (result.hasErrors) {
        System.err.println(
            "neko: $summary failed to compile with $errorCount error" +
                    if (errorCount == 1) "" else "s",
        )
        kotlin.system.exitProcess(1)
    }

    val output = result.outputDirectory
    if (output == null) {
        println("neko: compiled $summary (no output directory requested)")
        return
    }

    println(
        "neko: compiled $summary to ${result.classes.size} class file" +
            "${if (result.classes.size == 1) "" else "s"} in $output",
    )
    if (warningCount > 0) {
        println("neko: $warningCount warning${if (warningCount == 1) "" else "s"}")
    }
}

/** Reads one `.neko` file into a [SourceFile]. */
private fun readSource(path: Path): SourceFile = SourceFile(
    name = path.fileName?.toString() ?: path.toString(),
    content = Files.readString(path),
    path = path,
)

/** Every `.neko` file under [directory], sorted so a run is reproducible. */
private fun collectSources(directory: Path): List<SourceFile> = Files.walk(directory).use { stream ->
    stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".${Compiler.SOURCE_EXTENSION}") }
        .sorted()
        .map { readSource(it) }
        .toList()
}

private const val USAGE = """Neko compiler

Usage: neko <file-or-directory>... [options]

Arguments:
  Each argument is an .neko file or a directory containing them. A directory
  contributes every .neko file directly inside it. All of them are compiled as one
  program, so they can name each other's classes.

Options:
  --output <directory>   Where to write the compiled class files. A relative path is
                         resolved against the current directory, so the classes can be
                         kept inside the project. Defaults to the current directory.
  --color, --no-color    Force or suppress coloured diagnostics.
                         By default colour is used only on an interactive terminal.
  -h, --help             Show this message.
"""

/** The parsed command line, or `null` when the user asked for help. */
private class CommandLine(
    val sources: List<Path>,
    val outputDirectory: Path?,
    val useColor: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): CommandLine? {
            val sources = mutableListOf<Path>()
            var output: String? = null
            var color: Boolean? = null

            var index = 0
            while (index < args.size) {
                when (val argument = args[index]) {
                    "-h", "--help" -> return null

                    "--output", "-o" -> {
                        index++
                        if (index >= args.size) {
                            throw CommandLineException("'$argument' requires a directory argument")
                        }
                        output = args[index]
                    }

                    "--color" -> color = true
                    "--no-color" -> color = false

                    else -> {
                        if (argument.startsWith("-") && argument.length > 1) {
                            throw CommandLineException("unknown option '$argument'")
                        }
                        // `Path` is itself `Iterable<Path>`, so `+=` would read as adding every
                        // element of the path rather than the path; only `add` is unambiguous.
                        sources.add(toPath(argument))
                    }
                }
                index++
            }

            if (sources.isEmpty()) {
                throw CommandLineException("no source file or directory was given")
            }
            return CommandLine(
                sources = sources,
                outputDirectory = output?.let { toPath(it) },
                useColor = color ?: RenderStyle.supportsColor(),
            )
        }

        private fun toPath(text: String): Path = try {
            Path.of(text).absolute()
        } catch (failure: InvalidPathException) {
            throw CommandLineException("'$text' is not a valid path: ${failure.reason}")
        }
    }
}

private class CommandLineException(message: String) : Exception(message)
