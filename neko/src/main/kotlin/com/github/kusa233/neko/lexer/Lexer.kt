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
            '\'' -> lexCharacterLiteral()
            in '0'..'9' -> lexNumberLiteral()
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

    /**
     * Scans a whole or fractional number literal.
     *
     * The shape decides the token's *kind*, and the kind decides the default type the parser records:
     * digits alone are an integer literal — `1L` an integer that may only be a `long` — while a
     * fraction, an exponent, or an `f`/`F` suffix makes it a `float` or a `double`. Nothing here
     * consults a context, because the lexer has none: `1` is typed by the analyzer, which is the pass
     * that can see what the literal was written into.
     *
     * A number that does not fit the widest type the language has is reported here rather than
     * silently clamped, because no context could rescue it: the value the author wrote is not a value
     * the JVM can hold.
     */
    private fun lexNumberLiteral() {
        val start = position
        while (!isAtEnd && peek() in '0'..'9') advance()

        // A fraction turns an integer into a decimal. A `.` that is *not* followed by a digit is a
        // member access — `1.foo` — so it is left for the symbol lexer to pick up.
        var fractional = false
        if (peek() == '.' && peek(1) in '0'..'9') {
            fractional = true
            advance()
            while (!isAtEnd && peek() in '0'..'9') advance()
        }

        // `1e3` and `1.5e-3` are decimals too, and an exponent is written without a dot. The whole
        // exponent — sign included — stays part of the literal's text so that the string handed to
        // `toDouble` is one it can read; `toDouble` accepts `e` and `E` as written.
        if (peek() == 'e' || peek() == 'E') {
            val afterExponent = peek(1)
            val signed = afterExponent == '+' || afterExponent == '-'
            val digitAfter = if (signed) peek(2) else afterExponent
            if (digitAfter in '0'..'9') {
                fractional = true
                advance()
                if (signed) advance()
                while (!isAtEnd && peek() in '0'..'9') advance()
            }
        }

        // The suffix names the type the author asked for. Only `f`/`F` also changes the *kind*: a
        // decimal is a `double` unless it says otherwise, and `L` on a whole number is a request for
        // the 64-bit range without changing the token's shape.
        var suffixedFloat = false
        var longSuffixed = false
        when {
            peek() == 'f' || peek() == 'F' -> {
                suffixedFloat = true
                fractional = true
                advance()
            }

            !fractional && (peek() == 'l' || peek() == 'L') -> {
                longSuffixed = true
                advance()
            }
        }

        val literalText = text.substring(start, position)
        if (fractional) {
            lexFloatingLiteral(start, literalText, suffixedFloat)
            return
        }

        // A whole number: the digits alone, or the digits with the `L` that asked for 64 bits. The
        // `L` is stripped before parsing, so `parseNumberMagnitude` sees only what it can decode.
        val digits = if (longSuffixed) literalText.dropLast(1) else literalText
        val magnitude = parseNumberMagnitude(digits)
        if (magnitude == null) {
            diagnostics.error(
                "integer literal '$literalText' does not fit in a 64-bit signed integer " +
                    "(maximum is ${Long.MAX_VALUE}); a Neko program has no wider whole number",
                spanFrom(start),
            )
        }
        tokens += Token(
            TokenType.INTEGER_LITERAL,
            literalText,
            spanFrom(start),
            // A literal that overflowed is still handed on as the largest value there is, so the
            // parser has a node to build and the reader gets the one diagnostic above rather than a
            // cascade of follow-on type errors.
            literalValue = magnitude ?: Long.MAX_VALUE,
        )
    }

    /**
     * Scans the fractional part of a decimal literal.
     *
     * The digits are collected into a `String` and read by `toDouble` rather than accumulated as a
     * number, because `toDouble` is the correctly-rounded conversion — accumulating `1/10` in a loop
     * would produce a different value from the one the source names.
     */
    private fun lexFloatingLiteral(start: Int, literalText: String, isFloatSuffixed: Boolean) {
        val digits = if (isFloatSuffixed) literalText.dropLast(1) else literalText
        val value = digits.toDoubleOrNull()
        if (value == null || value.isInfinite() || value.isNaN()) {
            diagnostics.error(
                "floating-point literal '$literalText' does not fit in a 64-bit double",
                spanFrom(start),
            )
        }
        tokens += Token(
            TokenType.FLOAT_LITERAL,
            literalText,
            spanFrom(start),
            floatValue = value ?: 0.0,
            isFloatSuffixed = isFloatSuffixed,
        )
    }

    /**
     * Scans `'a'`, including the same backslash escapes a string literal accepts.
     *
     * Exactly one character is allowed between the quotes, which is what makes the token's value a
     * `Char` rather than a string the analyzer would have to re-check. An escape is still one
     * character even though it is written as two, so `'\n'` is legal and `'ab'` is not.
     */
    private fun lexCharacterLiteral() {
        val start = position
        advance() // opening quote

        if (isAtEnd || peek() == '\n' || peek() == '\r') {
            // Point at the opening quote: that is where the author has to look to close it.
            diagnostics.error("unterminated character literal", spanFrom(start, minOf(position, start + 1)))
            tokens += Token(TokenType.CHAR_LITERAL, text.substring(start, position), spanFrom(start))
            return
        }

        val decoded = StringBuilder()
        if (peek() == '\\') {
            lexEscapeInto(decoded)
        } else {
            decoded.append(peek())
            advance()
        }
        val value = decoded.singleOrNull()

        if (peek() != '\'') {
            // Consume whatever else was written before reporting, so the message can name it and the
            // next token starts after the whole literal rather than in its middle.
            val remainderStart = position
            while (!isAtEnd && peek() != '\'' && peek() != '\n' && peek() != '\r') advance()
            val remainder = text.substring(remainderStart, position)
            if (peek() == '\'') advance()
            diagnostics.error(
                "a character literal holds exactly one character, but '${text.substring(start, position)}' " +
                    "holds ${if (value == null) "none" else "more than one"}" +
                    (if (remainder.isEmpty()) "" else "; write \"$remainder\" for a string"),
                spanFrom(start),
            )
            tokens += Token(TokenType.CHAR_LITERAL, text.substring(start, position), spanFrom(start))
            return
        }
        advance() // closing quote

        tokens += Token(
            TokenType.CHAR_LITERAL,
            text.substring(start, position),
            spanFrom(start),
            literalValue = (value ?: '?').code.toLong(),
        )
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

    /**
     * Consumes one backslash escape, appending its decoded character to [value].
     *
     * The same escapes serve string literals and character literals: a character literal whose
     * content is an escape is one character, so `'\n'` needs no separate path, and rejecting the
     * escapes here rather than in each caller keeps the two from drifting apart.
     */
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
            '?' -> emit(TokenType.QUESTION, start)
            '+' -> if (match('+')) emit(TokenType.PLUS_PLUS, start) else emit(TokenType.PLUS, start)
            '*' -> emit(TokenType.STAR, start)
            '/' -> emit(TokenType.SLASH, start)
            '%' -> emit(TokenType.PERCENT, start)

            // `->` is checked before `--`, so `a -> b` keeps lexing as an arrow; the two never compete
            // for the same second character.
            '-' -> when {
                match('>') -> emit(TokenType.ARROW, start)
                match('-') -> emit(TokenType.MINUS_MINUS, start)
                else -> emit(TokenType.MINUS, start)
            }
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

/**
 * Reads the digits of a whole-number literal, or `null` when the value does not fit a signed 64-bit
 * integer.
 *
 * Accumulated by hand rather than through `toLongOrNull` so that an overflow is *detected* rather
 * than thrown: the caller has a span and a diagnostic to produce, and the value is still wanted as a
 * node so the rest of the file can be analysed.
 *
 * The guard is the usual one — reject before multiplying when the result would pass `Long.MAX_VALUE`
 * — and it works on the magnitude only, because the sign of a literal is a `-` operator the parser
 * sees and not part of this token.
 */
private fun parseNumberMagnitude(digits: String): Long? {
    var accumulated = 0L
    for (character in digits) {
        val digit = character.digitToIntOrNull(10) ?: return null
        if (accumulated > (Long.MAX_VALUE - digit) / 10) return null
        accumulated = accumulated * 10 + digit
    }
    return accumulated
}

private fun isIdentifierStart(character: Char): Boolean =
    character == '_' || character.isLetter()

private fun isIdentifierPart(character: Char): Boolean =
    character == '_' || character.isLetterOrDigit()
