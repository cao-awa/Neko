package com.github.kusa233.nekoj

import com.github.kusa233.neko.lexer.TokenType
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * Colours Neko source, one token at a time.
 *
 * The mapping is from the compiler's own tokens — via [NekoHighlightingLexer], which passes them
 * through unchanged — to the platform's standard colour keys. Standard keys rather than new ones
 * means Neko inherits the reader's existing theme: keywords follow the keyword colour they already
 * chose for Java or Kotlin, and every other language in the IDE.
 */
class NekoSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = NekoHighlightingLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        KEYS[tokenType] ?: TextAttributesKey.EMPTY_ARRAY
}

private fun single(key: TextAttributesKey): Array<TextAttributesKey> = arrayOf(key)

private fun buildKeys(): Map<IElementType, Array<TextAttributesKey>> {
    // Declared here rather than as a file-level property: a top-level property is initialized in
    // declaration order, so one declared below KEYS would still be null while KEYS was being built.
    val operatorTokens = listOf(
        TokenType.ASSIGN,
        TokenType.PLUS,
        TokenType.MINUS,
        TokenType.STAR,
        TokenType.SLASH,
        TokenType.PERCENT,
        TokenType.EQUALS_EQUALS,
        TokenType.BANG_EQUALS,
        TokenType.LESS,
        TokenType.LESS_EQUALS,
        TokenType.GREATER,
        TokenType.GREATER_EQUALS,
        TokenType.AND_AND,
        TokenType.OR_OR,
        TokenType.BANG,
        TokenType.ARROW,
        TokenType.COLON,
    )

    return HashMap<IElementType, Array<TextAttributesKey>>().apply {
        // Every reserved word the compiler knows, derived from TokenType rather than listed again.
        for (elementType in NekoTokens.keywordElementTypes) {
            put(elementType, single(DefaultLanguageHighlighterColors.KEYWORD))
        }

        put(NekoTokens.of(TokenType.IDENTIFIER), single(DefaultLanguageHighlighterColors.IDENTIFIER))
        put(NekoTokens.of(TokenType.INTEGER_LITERAL), single(DefaultLanguageHighlighterColors.NUMBER))
        put(NekoTokens.of(TokenType.STRING_LITERAL), single(DefaultLanguageHighlighterColors.STRING))

        put(NekoTokens.LINE_COMMENT, single(DefaultLanguageHighlighterColors.LINE_COMMENT))
        put(NekoTokens.BLOCK_COMMENT, single(DefaultLanguageHighlighterColors.BLOCK_COMMENT))

        // Brackets and separators each get their own key so a theme can tell them apart.
        put(NekoTokens.of(TokenType.LEFT_BRACE), single(DefaultLanguageHighlighterColors.BRACES))
        put(NekoTokens.of(TokenType.RIGHT_BRACE), single(DefaultLanguageHighlighterColors.BRACES))
        put(NekoTokens.of(TokenType.LEFT_PAREN), single(DefaultLanguageHighlighterColors.PARENTHESES))
        put(NekoTokens.of(TokenType.RIGHT_PAREN), single(DefaultLanguageHighlighterColors.PARENTHESES))
        put(NekoTokens.of(TokenType.LEFT_BRACKET), single(DefaultLanguageHighlighterColors.BRACKETS))
        put(NekoTokens.of(TokenType.RIGHT_BRACKET), single(DefaultLanguageHighlighterColors.BRACKETS))
        put(NekoTokens.of(TokenType.COMMA), single(DefaultLanguageHighlighterColors.COMMA))
        put(NekoTokens.of(TokenType.SEMICOLON), single(DefaultLanguageHighlighterColors.SEMICOLON))
        put(NekoTokens.of(TokenType.DOT), single(DefaultLanguageHighlighterColors.DOT))

        // Neko has no dedicated colour for a colon or an arrow, and they read as operators anyway.
        for (type in operatorTokens) {
            put(NekoTokens.of(type), single(DefaultLanguageHighlighterColors.OPERATION_SIGN))
        }
    }
}

private val KEYS: Map<IElementType, Array<TextAttributesKey>> = buildKeys()
