package com.github.kusa233.neko.diagnostics

import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.source.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for diagnostic rendering.
 *
 * Diagnostic quality is a feature here, not an afterthought: the sample file encodes the reporting
 * rules as comments, so these tests pin down the `file:line:column: severity: message` header and
 * the caret that underlines the exact offending text.
 */
class DiagnosticRendererTest {

    private val source = SourceFile.of(
        "Sample.neko",
        """
        class Sample {
            fun variable() {
                str: string = "Hell world!"
                str: string = "new value"
            }
        }
        """.trimIndent() + "\n",
    )

    /** A span covering [text] on the 1-based [line], found by search so positions stay honest. */
    private fun spanOf(line: Int, text: String): Span {
        val lineText = source.lineText(line)
        val column = lineText.indexOf(text)
        check(column >= 0) { "line $line does not contain '$text'" }
        // Offset of this line's first character: every preceding line plus its newline.
        val lineStart = (1 until line).sumOf { source.lineText(it).length + 1 }
        return Span(source, lineStart + column, lineStart + column + text.length)
    }

    @Test
    fun `renders a header with file, line, column and severity`() {
        val diagnostic = Diagnostic(Severity.ERROR, "boom", spanOf(4, "str"))
        val rendered = DiagnosticRenderer.render(diagnostic)
        // `str` sits after the line's 8 spaces of indentation, so the column is 9.
        assertTrue(
            rendered.startsWith("Sample.neko:4:9: error: boom"),
            "unexpected header: ${rendered.lineSequence().first()}",
        )
    }

    @Test
    fun `underlines exactly the offending text`() {
        val rendered = DiagnosticRenderer.render(Diagnostic(Severity.ERROR, "boom", spanOf(4, "str")))
        val lines = rendered.lines()
        // Header, empty gutter line, quoted source line, caret line.
        assertEquals(4, lines.size, "expected a four-line rendering, got:\n$rendered")
        assertTrue(lines[0].startsWith("Sample.neko:4:9: error: boom"), "unexpected header: ${lines[0]}")
        assertEquals("|", lines[1].trim(), "expected an empty gutter line, got: '${lines[1]}'")
        assertTrue(lines[2].contains("str: string = \"new value\""), "should quote the line")

        val caretLine = lines[3]
        assertEquals(3, caretLine.count { it == '^' }, "expected 3 carets, got:\n$rendered")
        assertEquals(
            lines[2].indexOf("str"),
            caretLine.indexOf('^'),
            "the caret must sit under 'str', not under 'string':\n$rendered",
        )
    }

    @Test
    fun `renders notes after the primary diagnostic`() {
        val primary = Diagnostic(
            severity = Severity.ERROR,
            message = "variable 'str' is already declared in this scope",
            span = spanOf(4, "str"),
            notes = listOf(
                Diagnostic(Severity.NOTE, "previous declaration of 'str' is here", spanOf(3, "str")),
            ),
        )
        val rendered = DiagnosticRenderer.render(primary)
        assertTrue(
            rendered.contains("Sample.neko:3:9: note: previous declaration of 'str' is here"),
            "unexpected rendering:\n$rendered",
        )
        // The primary message comes first, the note after it.
        assertTrue(
            rendered.indexOf("error:") < rendered.indexOf("note:"),
            "the note should follow the error:\n$rendered",
        )
    }

    @Test
    fun `omits the snippet for a diagnostic with no location`() {
        val rendered = DiagnosticRenderer.render(Diagnostic(Severity.ERROR, "internal failure"))
        assertEquals("error: internal failure", rendered)
    }

    @Test
    fun `omits the snippet and location for a builtin span that covers no text`() {
        val rendered = DiagnosticRenderer.render(Diagnostic(Severity.ERROR, "boom", Span.EMPTY))
        assertEquals("error: boom", rendered)
        assertFalse(rendered.contains("<builtin>"), "a locationless span should not name a file")
    }

    @Test
    fun `pads the gutter so carets stay aligned on a wide line number`() {
        val long = SourceFile.of("Big.neko", (1..12).joinToString("\n") { "line $it" } + "\n")
        val start = long.content.indexOf("line 12")
        val rendered = DiagnosticRenderer.render(
            Diagnostic(Severity.ERROR, "boom", Span(long, start, start + "line".length)),
        )
        val lines = rendered.lines()
        assertEquals(
            lines[1].indexOf("line 12"),
            lines[2].indexOf('^'),
            "the caret must line up with the source:\n$rendered",
        )
    }

    @Test
    fun `colour is opt-in and never applied silently`() {
        val diagnostic = Diagnostic(Severity.ERROR, "boom", spanOf(4, "str"))
        assertFalse(
            DiagnosticRenderer.render(diagnostic, useColor = false).contains("\u001B["),
            "plain rendering must not emit escape sequences",
        )
        assertTrue(
            DiagnosticRenderer.render(diagnostic, useColor = true).contains("\u001B["),
            "coloured rendering should emit escape sequences",
        )
    }
}
