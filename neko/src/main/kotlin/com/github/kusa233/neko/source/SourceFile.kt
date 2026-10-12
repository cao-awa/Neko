package com.github.kusa233.neko.source

import java.nio.file.Path

/**
 * An immutable view of one Neko source file.
 *
 * The file owns the original [content] and a precomputed index of line starts, which together
 * allow any character [offset] to be turned into a precise 1-based line/column [SourcePosition].
 * Every diagnostic in the compiler is anchored to a [Span] over a `SourceFile`, so this class is
 * the single source of truth for "where exactly did that happen".
 */
class SourceFile(
    /** Name used in diagnostics; usually the path exactly as it was spelled on the command line. */
    val name: String,
    /** Full text of the file, line terminators included. */
    val content: String,
    /** Real path on disk, or `null` for in-memory sources (used heavily by the tests). */
    val path: Path? = null,
) {
    /** Offset at which each line starts. `lineStarts[0]` is always `0`. */
    private val lineStarts: IntArray = buildLineStarts(content)

    /** Number of lines; a trailing newline yields one final, empty line. */
    val lineCount: Int get() = lineStarts.size

    /** Resolves a character [offset] to its 1-based line/column. */
    fun positionOf(offset: Int): SourcePosition {
        val clamped = offset.coerceIn(0, content.length)
        // Binary search for the last line start <= clamped.
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= clamped) low = mid else high = mid - 1
        }
        return SourcePosition(line = low + 1, column = clamped - lineStarts[low] + 1, offset = clamped)
    }

    /** Text of the 1-based [line] without its line terminator. */
    fun lineText(line: Int): String {
        require(line in 1..lineStarts.size) { "line $line out of range 1..${lineStarts.size}" }
        val start = lineStarts[line - 1]
        var end = if (line < lineStarts.size) lineStarts[line] else content.length
        if (end > start && content[end - 1] == '\n') end--
        if (end > start && content[end - 1] == '\r') end--
        return content.substring(start, end)
    }

    /** Span covering the whole file, handy as a fallback for diagnostics without a better anchor. */
    fun fullSpan(): Span = Span(this, 0, content.length)

    override fun toString(): String = name

    companion object {
        /** Builds a `SourceFile` for an in-memory snippet, e.g. inside a test. */
        fun of(name: String, content: String): SourceFile = SourceFile(name, content)

        private fun buildLineStarts(content: String): IntArray {
            val starts = ArrayList<Int>(maxOf(16, content.length / 32))
            starts.add(0)
            for (index in content.indices) {
                if (content[index] == '\n') starts.add(index + 1)
            }
            return starts.toIntArray()
        }
    }
}

/** A 1-based [line]/[column] pair plus the raw character [offset] it came from. */
data class SourcePosition(val line: Int, val column: Int, val offset: Int) {
    override fun toString(): String = "$line:$column"
}

/**
 * A half-open `[start, end)` range of character offsets within a single [file].
 *
 * Spans are attached to every token and every AST node, which is what lets the compiler point at
 * *the exact offending expression* rather than just the enclosing line.
 */
class Span(val file: SourceFile, val start: Int, val end: Int) {
    init {
        require(start >= 0) { "span start must be >= 0 but was $start" }
        require(end >= start) { "span end ($end) must not precede start ($start)" }
    }

    val length: Int get() = end - start
    val isEmpty: Boolean get() = end <= start
    val startPosition: SourcePosition get() = file.positionOf(start)
    val endPosition: SourcePosition get() = file.positionOf(end)

    /** 1-based line of the first character. */
    val line: Int get() = startPosition.line

    /** 1-based column of the first character. */
    val column: Int get() = startPosition.column

    /** Raw source text covered by this span. */
    val text: String
        get() = file.content.substring(start.coerceAtMost(file.content.length), end.coerceAtMost(file.content.length))

    /** Smallest span covering both this span and [other]; both must belong to the same file. */
    fun through(other: Span): Span {
        require(file === other.file) { "cannot merge spans from ${file.name} and ${other.file.name}" }
        return Span(file, minOf(start, other.start), maxOf(end, other.end))
    }

    override fun toString(): String = "${file.name}:$line:$column"

    companion object {
        /**
         * A span that points at no source text.
         *
         * Language built-ins such as `println` are not declared anywhere in the program, so they
         * carry this span; the renderer recognises it and omits the source snippet.
         */
        val EMPTY: Span by lazy { Span(SourceFile("<builtin>", ""), 0, 0) }
    }
}
