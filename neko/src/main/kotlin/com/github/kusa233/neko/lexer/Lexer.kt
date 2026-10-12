package com.github.kusa233.neko.lexer

import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.source.Span

/**
 * Turns Neko source text into a token stream.
 *
 * Neko is newline-sensitive: a physical line break terminates a statement or declaration, exactly
 * as it does in Kotlin or Python. The lexer therefore emits explicit [TokenType.NEWLINE] tokens
 * (collapsing runs of blank lines into one) and the parser treats them as separators.
 *
 * Errors are reported with an exact [Span] rather than a whole line, so the renderer can underline
 * the single offending character — an unterminated string, an illegal escape, a stray symbol.
 */
class Lexer(private val source: SourceFile, private val diagnostics: DiagnosticBag) {

    private val text = source.content
    private var position = 0
    private val tokens = mutableListOf<Token>()

    /** Lexes the whole file. Always appends a final [TokenType.END_OF_FILE] token. */
    fun lex(): List<Token> {
        while (!isAtEnd) {
            lexToken()
        }
        appendEndOfFile()
        return tokens
    }

    // ---------------------------------------------------------------- driver

    private fun lexToken() {
        when (val current = peek()) {
            ' ', '\t', '\u000B', '\u000C' -> advance()
            '\r', '\n' -> lexNewline()
            '/' -> lexSlashOrComment()
            '"' -> lexStringLiteral()
            in '0'..'9' -> lexIntegerLiteral()
            else -> when {
                isIdentifierStart(current) -> lexIdentifierOrKeyword()
                else -> lexSymbol()
            }
        }
    }

    // ------------------------------------------------------------- trivia

    private fun lexNewline() {
        val start = position
        // Collapse a whole run of blank lines into one NEWLINE token; this keeps the parser simple
        // while `span` still covers every character consumed.
        while (!isAtEnd && (peek() == '\r' || peek() == '\n')) advance()
        // A NEWLINE leading the file, or directly following another NEWLINE, carries no information.
        if (tokens.isEmpty() || tokens.last().type == TokenType.NEWLINE) return
        tokens += Token(TokenType.NEWLINE, text.substring(start, position), spanFrom(start))
    }

    private fun lexSlashOrComment() {
        when (peek(1)) {
            '/' -> {
                while (!isAtEnd && peek() != '\n' && peek() != '\r') advance()
            }

            '*' -> {
                val start = position
                advance()
                advance()
                var closed = false
                while (!isAtEnd) {
                    if (peek() == '*' && peek(1) == '/') {
                        advance()
                        advance()
                        closed = true
                        break
                    }
                    advance()
                }
                if (!closed) {
                    diagnostics.error("unterminated block comment", spanFrom(start))
                }
            }

            else -> lexSymbol()
        }
    }

    // ------------------------------------------------------------ literals

    private fun lexIntegerLiteral() {
        val start = position
        while (!isAtEnd && peek() in '0'..'9') advance()
        val literalText = text.substring(start, position)
        val value = literalText.toLongOrNull()
        if (value == null) {
            diagnostics.error(
                "integer literal '$literalText' does not fit in a 32-bit signed integer",
                spanFrom(start),
            )
            tokens += Token(TokenType.INTEGER_LITERAL, literalText, spanFrom(start), integerValue = 0L)
            return
        }
        if (value > Int.MAX_VALUE) {
            diagnostics.error(
                "integer literal '$literalText' does not fit in a 32-bit signed integer " +
                    "(maximum is ${Int.MAX_VALUE})",
                spanFrom(start),
            )
        }
        tokens += Token(TokenType.INTEGER_LITERAL, literalText, spanFrom(start), integerValue = value)
    }

    private fun lexStringLiteral() {
        val start = position
        advance() // opening quote
        val value = StringBuilder()
        while (true) {
            if (isAtEnd || peek() == '\n' || peek() == '\r') {
                // Point at the opening quote: that is where the author has to look to close it.
                diagnostics.error("unterminated string literal", spanFrom(start, minOf(position, start + 1)))
                tokens += Token(
                    TokenType.STRING_LITERAL,
                    text.substring(start, position),
                    spanFrom(start),
                    stringValue = value.toString(),
                )
                return
            }
            val current = peek()
            if (current == '"') {
                advance()
                break
            }
            if (current == '\\') {
                lexEscapeInto(value)
                continue
            }
            value.append(current)
            advance()
        }
        tokens += Token(
            TokenType.STRING_LITERAL,
            text.substring(start, position),
            spanFrom(start),
            stringValue = value.toString(),
        )
    }

    /** Consumes one backslash escape, appending its decoded character to [value]. */
    private fun lexEscapeInto(value: StringBuilder) {
        val escapeStart = position
        advance() // backslash
        if (isAtEnd) {
            diagnostics.error("unterminated escape sequence", spanFrom(escapeStart))
            return
        }
        val escape = peek()
        advance()
        when (escape) {
            'n' -> value.append('\n')
            't' -> value.append('\t')
            'r' -> value.append('\r')
            '0' -> value.append('\u0000')
            '\\' -> value.append('\\')
            '"' -> value.append('"')
            '\'' -> value.append('\'')
            else -> diagnostics.error(
                "invalid escape sequence '\\$escape' in string literal",
                spanFrom(escapeStart),
            )
        }
    }

    // --------------------------------------------------------- identifiers

    private fun lexIdentifierOrKeyword() {
        val start = position
        while (!isAtEnd && isIdentifierPart(peek())) advance()
        val identifierText = text.substring(start, position)
        val type = KEYWORDS[identifierText] ?: TokenType.IDENTIFIER
        tokens += Token(type, identifierText, spanFrom(start))
    }

    // ------------------------------------------------------------ symbols

    private fun lexSymbol() {
        val start = position
        val current = advance()
        when (current) {
            '{' -> emit(TokenType.LEFT_BRACE, start)
            '}' -> emit(TokenType.RIGHT_BRACE, start)
            '(' -> emit(TokenType.LEFT_PAREN, start)
            ')' -> emit(TokenType.RIGHT_PAREN, start)
            '[' -> emit(TokenType.LEFT_BRACKET, start)
            ']' -> emit(TokenType.RIGHT_BRACKET, start)
            ',' -> emit(TokenType.COMMA, start)
            ':' -> emit(TokenType.COLON, start)
            ';' -> emit(TokenType.SEMICOLON, start)
            '.' -> emit(TokenType.DOT, start)
            '+' -> emit(TokenType.PLUS, start)
            '*' -> emit(TokenType.STAR, start)
            '/' -> emit(TokenType.SLASH, start)
            '%' -> emit(TokenType.PERCENT, start)

            '-' -> if (match('>')) emit(TokenType.ARROW, start) else emit(TokenType.MINUS, start)
            '=' -> if (match('=')) emit(TokenType.EQUALS_EQUALS, start) else emit(TokenType.ASSIGN, start)
            '!' -> if (match('=')) emit(TokenType.BANG_EQUALS, start) else emit(TokenType.BANG, start)
            '<' -> if (match('=')) emit(TokenType.LESS_EQUALS, start) else emit(TokenType.LESS, start)
            '>' -> if (match('=')) emit(TokenType.GREATER_EQUALS, start) else emit(TokenType.GREATER, start)
            '&' -> if (match('&')) {
                emit(TokenType.AND_AND, start)
            } else {
                diagnostics.error("unexpected character '&'; did you mean '&&'?", spanFrom(start))
            }

            '|' -> if (match('|')) {
                emit(TokenType.OR_OR, start)
            } else {
                diagnostics.error("unexpected character '|'; did you mean '||'?", spanFrom(start))
            }

            else -> diagnostics.error("unexpected character '$current'", spanFrom(start))
        }
    }

    private fun emit(type: TokenType, start: Int) {
        tokens += Token(type, text.substring(start, position), spanFrom(start))
    }

    // --------------------------------------------------------------- helpers

    private fun appendEndOfFile() {
        // Anchored at the very end of the file so that "unexpected end of file" diagnostics point
        // just past the last character rather than at the start of the file.
        tokens += Token(TokenType.END_OF_FILE, "", Span(source, text.length, text.length))
    }

    private val isAtEnd: Boolean get() = position >= text.length

    private fun peek(lookahead: Int = 0): Char {
        val index = position + lookahead
        return if (index < text.length) text[index] else '\u0000'
    }

    private fun advance(): Char = text[position++]

    /** Consumes [expected] if it is next, reporting whether it was consumed. */
    private fun match(expected: Char): Boolean {
        if (isAtEnd || peek() != expected) return false
        advance()
        return true
    }

    private fun spanFrom(start: Int, end: Int = position): Span = Span(source, start, end)
}

private fun isIdentifierStart(character: Char): Boolean =
    character == '_' || character.isLetter()

private fun isIdentifierPart(character: Char): Boolean =
    character == '_' || character.isLetterOrDigit()
