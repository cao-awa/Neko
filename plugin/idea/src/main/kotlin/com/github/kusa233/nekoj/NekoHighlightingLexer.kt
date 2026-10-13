package com.github.kusa233.nekoj

import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.lexer.Lexer as NekoLexer
import com.github.kusa233.neko.lexer.TokenType
import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * The lexer the IDE uses, built on the compiler's [NekoLexer].
 *
 * The compiler's lexer is deliberately not an IDE lexer: it *discards* the two kinds of text an IDE
 * cannot do without. Spaces are stepped over without a token, and `//` and `/* */` comments produce
 * no token at all, because the parser has no use for either. An IDE, by contrast, needs a gap-free
 * token stream — comments must be colour-able and foldable, and the platform requires that
 * consecutive tokens tile the whole buffer.
 *
 * So this lexer runs the compiler's lexer over the text and then fills the holes. Everything the
 * compiler produced is passed through untouched, which is what keeps the colours in the IDE exactly
 * the tokens the compiler sees. The holes are re-read as trivia — whitespace, `//` comments,
 * `/* */` comments — and anything still unaccounted for (a stray `&`, which the compiler reports as
 * an error rather than tokenising) becomes [NekoTokens.BAD_CHARACTER] so nothing is ever lost.
 *
 * Trivia is reconstructed here rather than in the compiler because it is an editor-only concern;
 * the compiler's token stream stays exactly as the parser wants it.
 */
class NekoHighlightingLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var limit = 0
    private var lexemes: List<Lexeme> = emptyList()
    private var index = 0
    private var tokenStart = 0
    private var tokenEnd = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.limit = endOffset
        this.lexemes = scan(buffer.toString())
        // Skip whole lexemes that end before the requested start. Restarts always land on a lexeme
        // boundary this lexer itself produced, because the platform re-lexes from a recorded
        // position, so no lexeme is ever split.
        index = 0
        while (index < lexemes.size && lexemes[index].end <= startOffset) index++
        sync()
    }

    /**
     * Always `0`.
     *
     * The state exists so the platform can restart lexing in the middle of the buffer, and it has
     * to be enough to resume. It is not needed here: [start] always lexes the whole buffer from the
     * beginning and then fast-forwards, so any offset can be resumed exactly, and a restart inside
     * a string or a block comment still recovers that token's real start.
     */
    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? {
        if (index >= lexemes.size || tokenStart >= limit) return null
        return lexemes[index].type
    }

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = buffer.length

    override fun advance() {
        if (index < lexemes.size) index++
        sync()
    }

    private fun sync() {
        if (index < lexemes.size) {
            tokenStart = lexemes[index].start
            tokenEnd = lexemes[index].end
        } else {
            tokenStart = buffer.length
            tokenEnd = buffer.length
        }
    }
}

/** One token in the IDE's stream: what it is, and the half-open range it covers. */
private class Lexeme(val type: IElementType, val start: Int, val end: Int)

/**
 * Lexes [text] with the compiler's lexer and fills every gap it left.
 *
 * The compiler's lexer is run for its tokens only; the diagnostics it collects are dropped, because
 * the same text is compiled for real by the annotator, which reports errors far more accurately
 * than a lexer can. Here the tokens are used purely as the skeleton of the syntax tree.
 */
private fun scan(text: String): List<Lexeme> {
    val source = SourceFile(name = "highlight.neko", content = text)
    val lexed = try {
        NekoLexer(source, DiagnosticBag()).lex()
    } catch (error: RuntimeException) {
        // A lexer that cannot cope with the text being typed must not take the editor down with it.
        emptyList()
    }

    val result = ArrayList<Lexeme>(lexed.size + 16)
    var offset = 0
    for (token in lexed) {
        if (token.type == TokenType.END_OF_FILE) continue
        val start = token.span.start
        val end = token.span.end
        if (start < offset) continue
        if (start > offset) appendTrivia(text, offset, start, result)
        if (end > start) result += Lexeme(NekoTokens.of(token.type), start, end)
        if (end > offset) offset = end
    }
    if (offset < text.length) appendTrivia(text, offset, text.length, result)
    return result
}

/**
 * Re-reads `text[from, to)` — a stretch the compiler's lexer skipped — as whitespace and comments.
 *
 * A character that is none of those can only be one the compiler's lexer rejected outright; it
 * becomes a bad character so that the run stays gap-free and the editor marks it.
 */
private fun appendTrivia(text: String, from: Int, to: Int, out: MutableList<Lexeme>) {
    var position = from
    while (position < to) {
        val current = text[position]
        when {
            current == '/' && position + 1 < to && text[position + 1] == '/' -> {
                var end = position + 2
                while (end < to && text[end] != '\n' && text[end] != '\r') end++
                out += Lexeme(NekoTokens.LINE_COMMENT, position, end)
                position = end
            }

            current == '/' && position + 1 < to && text[position + 1] == '*' -> {
                var end = position + 2
                var closed = false
                while (end + 1 < to) {
                    if (text[end] == '*' && text[end + 1] == '/') {
                        closed = true
                        break
                    }
                    end++
                }
                // An unterminated block comment runs to the end of the gap; the annotator reports it.
                val commentEnd = if (closed) end + 2 else to
                out += Lexeme(NekoTokens.BLOCK_COMMENT, position, commentEnd)
                position = commentEnd
            }

            isSpace(current) -> {
                var end = position
                while (end < to && isSpace(text[end])) end++
                out += Lexeme(NekoTokens.WHITE_SPACE, position, end)
                position = end
            }

            else -> {
                out += Lexeme(NekoTokens.BAD_CHARACTER, position, position + 1)
                position++
            }
        }
    }
}

private fun isSpace(character: Char): Boolean = when (character) {
    ' ', '\t', '\n', '\r', '\u000B', '\u000C' -> true
    else -> false
}
