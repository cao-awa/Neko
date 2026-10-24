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
            "locate", "import", "meow", "class", "interface", "constructor", "fun", "val", "var",
            "private", "public", "protected", "static", "return", "yield", "if", "else", "while",
            "when", "nya", "try", "scratch", "nyan", "emit", "this", "true", "false",
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
    fun `removed keywords scan as ordinary identifiers`() {
        // `throw` and `throws` went first: raising is `raise` plus an `emit` clause. `catch` and
        // `finally` followed when the `try`/`catch` statement was removed — a false `nya` condition
        // is now answered by a block rather than by a handler. Scanning each as an identifier is what
        // makes a stale program fail with "there is no function named 'throw'" rather than a syntax
        // error, and it keeps the words usable as ordinary names.
        for (removed in listOf("throw", "throws", "catch", "finally")) {
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
    fun `scans a long suffix as part of the literal`() {
        // The suffix is not a separate token and not an identifier: `1L` is one whole-number literal
        // whose text says it asked for 64 bits, which is how the parser knows to type it as a `long`.
        val literal = lex("a: long = 1L").first.first { it.type == TokenType.INTEGER_LITERAL }
        assertEquals("1L", literal.text)
        assertEquals(1L, literal.literalValue)
    }

    @Test
    fun `scans fractional literals and their type suffix`() {
        val tokens = lex("a: float = 1.5f\nb: double = 1.5\nc: float = 2F").first
            .filter { it.type == TokenType.FLOAT_LITERAL }
        assertEquals(listOf("1.5f", "1.5", "2F"), tokens.map { it.text })
        assertEquals(listOf(true, false, true), tokens.map { it.isFloatSuffixed })
        assertEquals(listOf(1.5, 1.5, 2.0), tokens.map { it.floatValue })
    }

    @Test
    fun `an integer literal may be as wide as a long but no wider`() {
        val literal = lex("a: long = 4000000000").first.first { it.type == TokenType.INTEGER_LITERAL }
        assertEquals(4000000000L, literal.literalValue)
        assertTrue(
            !lex("a: long = 4000000000").second.hasErrors,
            "4000000000 fits in a long and must not be rejected by the lexer",
        )
        assertTrue(
            lex("a: long = 99999999999999999999").second.hasErrors,
            "a value beyond a long must be reported",
        )
    }

    @Test
    fun `scans a character literal and decodes its escapes`() {
        val plain = lex("c: char = 'a'").first.first { it.type == TokenType.CHAR_LITERAL }
        assertEquals("'a'", plain.text)
        assertEquals('a'.code.toLong(), plain.literalValue)

        val escaped = lex("""c: char = '\n'""").first.first { it.type == TokenType.CHAR_LITERAL }
        assertEquals('\n'.code.toLong(), escaped.literalValue, "an escape is one character")

        val quote = lex("""c: char = '\''""").first.first { it.type == TokenType.CHAR_LITERAL }
        assertEquals('\''.code.toLong(), quote.literalValue)
    }

    @Test
    fun `reports a character literal that is not one character`() {
        assertTrue(lex("c: char = 'ab'").second.hasErrors, "'ab' is two characters")
        assertTrue(lex("c: char = ''").second.hasErrors, "'' is none")
        assertTrue(lex("c: char = 'a").second.hasErrors, "an unclosed literal must be reported")
    }

    @Test
    fun `always ends with an end-of-file token`() {
        assertEquals(TokenType.END_OF_FILE, lex("").first.last().type)
        assertEquals(TokenType.END_OF_FILE, lex("a: int = 1").first.last().type)
    }
}
