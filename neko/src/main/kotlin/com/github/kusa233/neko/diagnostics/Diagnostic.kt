package com.github.kusa233.neko.diagnostics

import com.github.kusa233.neko.source.Span

/** How serious a [Diagnostic] is. Only [ERROR] prevents the compiler from emitting bytecode. */
enum class Severity(val label: String) {
    ERROR("error"),
    WARNING("warning"),
    NOTE("note"),
}

/**
 * A single compiler message.
 *
 * [span] is the *exact* point of interest — the renderer turns it into a `line:column` header plus a
 * caret line underlining the offending characters. [notes] carry the secondary locations that make a
 * message actionable (for example "previous declaration is here").
 */
class Diagnostic(
    val severity: Severity,
    val message: String,
    val span: Span? = null,
    val notes: List<Diagnostic> = emptyList(),
    /**
     * The `import` clause that would fix this diagnostic, without the keyword, or `null`.
     *
     * This is machine-readable advice rather than prose: the message already says the same thing in
     * words, but an editor cannot act on a sentence. Keeping it as a field is what lets the IDE offer
     * a quick fix that inserts exactly the clause the compiler would have wanted, instead of
     * re-deriving the answer — and, more importantly, instead of deriving a *different* one. Only the
     * analyzer knows which packages the file already imports and which the platform actually has.
     *
     * It stays `null` whenever no single import is correct: a name two packages both declare has no
     * right answer to offer.
     */
    val suggestedImport: String? = null,
) {
    /** Convenience factory for the secondary locations attached to a primary diagnostic. */
    fun note(message: String, span: Span?): Diagnostic = Diagnostic(Severity.NOTE, message, span)

    override fun toString(): String = DiagnosticRenderer.render(this)
}

/**
 * Decides whether diagnostics should be coloured.
 *
 * Colour is enabled only on an interactive terminal, so redirected output and CI logs stay plain
 * text. The conventional `NO_COLOR` and `FORCE_COLOR` environment variables are honoured.
 */
object RenderStyle {
    fun supportsColor(): Boolean {
        if (!System.getenv("NO_COLOR").isNullOrEmpty()) return false
        if (!System.getenv("FORCE_COLOR").isNullOrEmpty()) return true
        // `Console.isTerminal` reflects whether stdout is attached to a terminal.
        return System.console() != null
    }
}

/**
 * Renders diagnostics in the familiar, source-oriented style:
 *
 * ```
 * Sample.neko:4:9: error: variable 'str' is already declared in this scope
 *   |
 * 4 |         str: string = "new value"
 *   |         ^^^
 * Sample.neko:3:9: note: previous declaration of 'str' is here
 *   |
 * 3 |         str: string = "Hell world!"
 *   |         ^^^
 * ```
 *
 * The leading bar line is an empty gutter, which gives the source and caret lines a stable left
 * edge to align against.
 */
object DiagnosticRenderer {
    /** Renders a diagnostic together with its notes. The result never ends with a newline. */
    fun render(diagnostic: Diagnostic, useColor: Boolean = false): String = buildString {
        append(renderSingle(diagnostic, useColor))
        for (note in diagnostic.notes) {
            append('\n')
            append(renderSingle(note, useColor))
        }
    }

    private fun renderSingle(diagnostic: Diagnostic, useColor: Boolean): String = buildString {
        append(renderHeader(diagnostic, useColor))
        val snippet = renderSnippet(diagnostic)
        if (snippet.isNotEmpty()) {
            append('\n')
            append(snippet)
        }
    }

    private fun renderHeader(diagnostic: Diagnostic, useColor: Boolean): String {
        // A span covering no text marks a location outside the program (a built-in, say). Naming its
        // synthetic file would be noise, so the message stands alone.
        val span = diagnostic.span?.takeUnless { it.isEmpty }
        val location = span?.let { "${it.file.name}:${it.line}:${it.column}: " } ?: ""
        val severity = diagnostic.severity.label
        val painted = when {
            !useColor -> severity
            diagnostic.severity == Severity.ERROR -> "\u001B[1;31m$severity\u001B[0m"
            diagnostic.severity == Severity.WARNING -> "\u001B[1;33m$severity\u001B[0m"
            else -> "\u001B[1;36m$severity\u001B[0m"
        }
        return "$location$painted: ${diagnostic.message}"
    }

    private fun renderSnippet(diagnostic: Diagnostic): String {
        val span = diagnostic.span ?: return ""
        // An empty span marks a location outside the program text (a built-in, say); there is no
        // source to quote, so the header stands on its own.
        if (span.isEmpty) return ""
        val file = span.file
        val line = span.line
        if (line !in 1..file.lineCount) return ""

        // Tabs are rendered as a single space so that caret columns stay aligned with the source.
        val text = file.lineText(line).replace('\t', ' ')
        val numberWidth = maxOf(2, line.toString().length)
        val gutter = " ".repeat(numberWidth)
        val caretColumn = (span.column - 1).coerceAtLeast(0)
        val caretWidth = caretWidth(span, line, text)
        val renderedLine = text.padEnd(caretColumn + caretWidth)

        return buildString {
            append(gutter).append(" |\n")
            append(line.toString().padStart(numberWidth)).append(" | ").append(renderedLine).append('\n')
            append(gutter).append(" | ").append(" ".repeat(caretColumn)).append("^".repeat(caretWidth))
        }
    }

    private fun caretWidth(span: Span, line: Int, renderedLine: String): Int {
        val endPosition = span.endPosition
        val width = if (endPosition.line == line) {
            endPosition.column - span.column
        } else {
            // Multi-line span: underline the remainder of the first line.
            renderedLine.length - (span.column - 1)
        }
        return width.coerceAtLeast(1)
    }
}
