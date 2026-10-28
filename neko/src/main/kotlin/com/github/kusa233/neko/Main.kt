package com.github.kusa233.neko

import com.github.kusa233.neko.diagnostics.RenderStyle
import com.github.kusa233.neko.diagnostics.Severity
import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.source.StandardLibrary
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
 * neko <file-or-directory>... [--output <directory>] [--run <class>]
 * ```
 *
 * Each argument may be a `.neko` file or a directory of them; a directory contributes every `.neko`
 * file directly inside it. Several may be given because an Neko program is compiled as a whole —
 * a program and the standard library it names have to see each other's declarations.
 *
 * On success the compiled class files are written (when an output directory was given) and `0` is
 * returned; when the program fails to compile every diagnostic is printed and the exit status is `1`,
 * so the compiler composes with ordinary build tooling. With `--run`, compilation is followed by
 * running the named class' `main` instead of printing the compilation summary — the classes are
 * defined from the compilation's own bytes, so no output directory is required and no older copy on
 * disk can answer instead. `2` is reserved for an internal compiler error, which must never be
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

    // The standard library: the copy the compiler carries, or the reader's own. A `--stdlib-path` that
    // is missing, or holds no `.neko` file, is reported rather than silently falling back to the
    // packaged library: the reader asked for a specific library, and quietly compiling against a
    // different one would make a typo in the path look like a working build.
    val library = options.standardLibraryPath?.let { directory ->
        if (!directory.exists() || !directory.isDirectory()) {
            System.err.println("neko: --stdlib-path is not a directory: $directory")
            kotlin.system.exitProcess(1)
        }
        val found = try {
            StandardLibrary.sourcesIn(directory)
        } catch (failure: Exception) {
            System.err.println("neko: cannot read $directory: ${failure.message}")
            kotlin.system.exitProcess(1)
        }
        if (found.isEmpty()) {
            System.err.println(
                "neko: no .${Compiler.SOURCE_EXTENSION} files in $directory, so the standard " +
                    "library would be empty",
            )
            kotlin.system.exitProcess(1)
        }
        found
    } ?: StandardLibrary.sources()

    val summary = if (options.sources.size == 1) options.sources.single().toString() else {
        "${options.sources.size} paths"
    }

    val result = try {
        Compiler.compileProgram(sources, options.outputDirectory, library)
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

    // `--run` makes the program's own output the point of the invocation, so the compilation summary
    // would only be a line between the reader and what they asked to see. Diagnostics above are still
    // printed: a warning is part of the answer, even when the program runs.
    options.runClass?.let { className ->
        val status = runCompiled(result, className)
        if (status != 0) kotlin.system.exitProcess(status)
        return
    }

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

/**
 * Runs [className]'s `main` and answers the process status, `0` when it returned normally.
 *
 * The program was compiled successfully by the time this is called, so every remaining problem is a
 * problem with running it: the name may not have been compiled, the class may lack the JVM entry
 * point, or `main` may have thrown. They all exit `1`, like `java` does for an uncaught exception.
 */
private fun runCompiled(result: CompilationResult, className: String): Int =
    when (val outcome = ProgramRunner.run(result.classes, className)) {
        ProgramRunner.Outcome.Returned -> 0

        is ProgramRunner.Outcome.MissingClass -> {
            System.err.println(
                "neko: no class named '$className' was compiled; the name is fully qualified " +
                    "and written in dot form, such as neko.sample.TestClass",
            )
            1
        }

        is ProgramRunner.Outcome.MissingMain -> {
            System.err.println(
                "neko: class '$className' has no 'static main(String[])' to run",
            )
            1
        }

        is ProgramRunner.Outcome.Failed -> {
            System.err.println("neko: '$className' failed while running")
            outcome.cause.printProgramStackTrace()
            1
        }
    }

/**
 * Prints [this] the way an uncaught exception is printed, without the reflection frames the runner
 * used to reach `main`.
 *
 * Those frames belong to the `--run` plumbing rather than to the program, and a reader debugging
 * their own code should see the frames that start at `main`. Trimming is presentation only: the
 * [Throwable] itself is unchanged, and its message and the program's own frames stay in order.
 */
private fun Throwable.printProgramStackTrace() {
    val runnerFrames = stackTrace.filterNot { frame ->
        frame.className == "com.github.kusa233.neko.ProgramRunner" ||
            frame.className == "com.github.kusa233.neko.MainKt" ||
            frame.className.startsWith("java.lang.reflect.") ||
            frame.className.startsWith("jdk.internal.reflect.") ||
            frame.className.startsWith("sun.reflect.")
    }
    if (runnerFrames.isEmpty()) {
        printStackTrace()
        return
    }
    stackTrace = runnerFrames.toTypedArray()
    printStackTrace()
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
                         kept inside the project. When omitted, no class files are
                         written; the compilation only lives long enough to report its
                         diagnostics, or to be run with --run.
  --run <class>          After compiling, run the static main(String[]) of <class>. The
                         class name is fully qualified and written in dot form, such as
                         neko.sample.TestClass. Its output goes straight to this terminal,
                         and the compilation summary is not printed.
  --stdlib-path <dir>    Compile against the standard library in <dir> instead of the one
                         the compiler carries. The directory is searched recursively for
                         .neko files. Use it to work on the library itself, or to pin a
                         version of it for a project.
  --color, --no-color    Force or suppress coloured diagnostics.
                         By default colour is used only on an interactive terminal.
  -h, --help             Show this message.

Exit status:
  0  the program compiled and, with --run, its main returned.
  1  the program failed to compile, or --run could not find or finish it.
  2  an internal compiler error.
"""

/** The parsed command line, or `null` when the user asked for help. */
internal class CommandLine(
    val sources: List<Path>,
    val outputDirectory: Path?,
    val standardLibraryPath: Path?,
    /** The fully-qualified class `--run` asked for, in dot form. */
    val runClass: String?,
    val useColor: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): CommandLine? {
            val sources = mutableListOf<Path>()
            var output: String? = null
            var standardLibrary: String? = null
            var runClass: String? = null
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

                    "--run" -> {
                        index++
                        if (index >= args.size) {
                            throw CommandLineException("'$argument' requires a class name")
                        }
                        runClass = args[index]
                    }

                    "--stdlib-path" -> {
                        index++
                        if (index >= args.size) {
                            throw CommandLineException("'$argument' requires a directory argument")
                        }
                        standardLibrary = args[index]
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

            if (runClass?.isBlank() == true) {
                throw CommandLineException("'--run' requires a class name")
            }
            if (sources.isEmpty()) {
                throw CommandLineException("no source file or directory was given")
            }
            return CommandLine(
                sources = sources,
                outputDirectory = output?.let { toPath(it) },
                standardLibraryPath = standardLibrary?.let { toPath(it) },
                runClass = runClass,
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

internal class CommandLineException(message: String) : Exception(message)
