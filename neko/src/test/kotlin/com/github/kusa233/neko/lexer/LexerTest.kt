package com.github.kusa233.neko.lexer

import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.source.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the scanner.
 *
 * The lexer is where positions are first assigned, so several of these assert on the exact
 * line/column of a token: every later diagnostic is only as precise as the spans produced here.
 */
class LexerTest {

    private fun lex(source: String): Pair<List<Token>, DiagnosticBag> {
        val diagnostics = DiagnosticBag()
        val tokens = Lexer(SourceFile.of("Test.neko", source), diagnostics).lex()
        return tokens to diagnostics
    }

    private fun types(source: String): List<TokenType> =
        lex(source).first.map { it.type }.filter { it != TokenType.NEWLINE && it != TokenType.END_OF_FILE }

    @Test
    fun `scans identifiers, literals and punctuation`() {
        assertEquals(
            listOf(
                TokenType.IDENTIFIER, TokenType.COLON, TokenType.IDENTIFIER, TokenType.ASSIGN,
                TokenType.INTEGER_LITERAL,
            ),
            types("value: int = 42"),
        )
    }

    @Test
    fun `scans every keyword`() {
        val keywords = listOf(
            "locate", "import", "class", "interface", "constructor", "fun", "val", "var", "private",
            "public", "protected", "static", "return", "yield", "if", "else", "while",
            "when", "try", "catch", "finally", "raise", "emit", "this", "true", "false",
        )
        for (keyword in keywords) {
            val scanned = types(keyword).single()
            assertTrue(
                scanned != TokenType.IDENTIFIER,
                "'$keyword' should be scanned as a keyword, not an identifier",
            )
        }
    }

    @Test
    fun `throw and throws are no longer keywords`() {
        // Both were removed: raising is `raise` plus an `emit` clause, and catching what another JVM
        // language throws is `try`/`catch`/`finally`. Scanning them as identifiers is what makes a
        // stale program fail with "there is no function named 'throw'" rather than a syntax error.
        for (removed in listOf("throw", "throws")) {
            assertEquals(
                TokenType.IDENTIFIER,
                types(removed).single(),
                "'$removed' must scan as an ordinary identifier",
            )
        }
    }

    @Test
    fun `scans multi-character operators as one token`() {
        assertEquals(
            listOf(
                TokenType.EQUALS_EQUALS, TokenType.BANG_EQUALS, TokenType.LESS_EQUALS,
                TokenType.GREATER_EQUALS, TokenType.AND_AND, TokenType.OR_OR, TokenType.ARROW,
            ),
            types("== != <= >= && || ->"),
        )
    }

    @Test
    fun `records the exact position of each token`() {
        // With the columns spelled out: `value` 1, `:` 6, `int` 8, `=` 12, `42` 14.
        val tokens = lex("value: int = 42").first
            .filter { it.type != TokenType.NEWLINE && it.type != TokenType.END_OF_FILE }
        assertEquals(listOf(1, 6, 8, 12, 14), tokens.map { it.column })
        assertTrue(tokens.all { it.line == 1 }, "every token is on the first line")
    }

    @Test
    fun `tracks positions across lines`() {
        val tokens = lex("a: int = 1\nb: int = 2").first
        val second = tokens.first { it.text == "b" }
        assertEquals(2, second.line)
        assertEquals(1, second.column)
    }

    @Test
    fun `decodes string escapes`() {
        val literal = lex("""s: string = "a\nb\tc\\d\"e"""").first
            .first { it.type == TokenType.STRING_LITERAL }
        assertEquals("a\nb\tc\\d\"e", literal.stringValue)
    }

    @Test
    fun `skips line and block comments`() {
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.IDENTIFIER),
            types("// a comment\nvalue /* inline */ other"),
        )
    }

    @Test
    fun `treats a blank line as a single separator`() {
        val newlines = lex("a\n\n\nb").first.count { it.type == TokenType.NEWLINE }
        assertEquals(1, newlines, "consecutive newlines should collapse into one separator")
    }

    @Test
    fun `reports an unterminated string at its opening quote`() {
        val diagnostics = lex("""s: string = "unterminated""").second
        val error = diagnostics.diagnostics.single()
        assertEquals(13, error.span?.column, "the error should point at the opening quote")
        assertTrue(error.message.contains("unterminated"), "unexpected message: ${error.message}")
    }

    @Test
    fun `suggests the doubled operator for a single ampersand`() {
        val diagnostics = lex("a && b & c").second
        val error = diagnostics.diagnostics.single()
        assertTrue(
            error.message.contains("&&"),
            "the message should suggest '&&', but was: ${error.message}",
        )
    }

    @Test
    fun `reports an integer literal that does not fit in an int`() {
        val diagnostics = lex("a: int = 99999999999999999999").second
        assertTrue(diagnostics.hasErrors)
    }

    @Test
    fun `always ends with an end-of-file token`() {
        assertEquals(TokenType.END_OF_FILE, lex("").first.last().type)
        assertEquals(TokenType.END_OF_FILE, lex("a: int = 1").first.last().type)
    }
}
