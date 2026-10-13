package com.github.kusa233.nekoj

import com.github.kusa233.neko.lexer.TokenType
import com.intellij.psi.TokenType as PsiTokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * The IntelliJ [IElementType]s that stand in for the compiler's own [TokenType]s.
 *
 * The Neko compiler describes its vocabulary with an enum, and an IDE has to describe the same
 * vocabulary with [IElementType] objects that carry a [com.intellij.lang.Language]. Rather than
 * hand-writing a second list that could drift from the first, every element type here is created
 * from [TokenType.values], so adding a keyword to the compiler automatically adds it to the IDE.
 *
 * The reserved-word table is likewise re-derived instead of copied: [keywords] reads the
 * compiler's `display` strings, which spell a keyword as `'class'` and punctuation as `'{'`, so
 * "quoted and made of letters" identifies a keyword without a second list to keep in sync.
 */
object NekoTokens {

    /** The root of every Neko syntax tree. */
    val FILE: IFileElementType = IFileElementType("NEKO_FILE", NekoLanguage.INSTANCE)

    // Whitespace and bad characters are the platform's own types, so the editor, the formatter and
    // every other piece of the IDE that looks for `TokenType.WHITE_SPACE` finds them.
    val WHITE_SPACE: IElementType = PsiTokenType.WHITE_SPACE
    val BAD_CHARACTER: IElementType = PsiTokenType.BAD_CHARACTER

    // Comments are not platform types: the compiler has no comment token at all, so the IDE
    // invents these two to give comments a colour, a fold region and real PSI.
    val LINE_COMMENT: IElementType = IElementType("NEKO_LINE_COMMENT", NekoLanguage.INSTANCE)
    val BLOCK_COMMENT: IElementType = IElementType("NEKO_BLOCK_COMMENT", NekoLanguage.INSTANCE)

    private val bySyntax: Map<TokenType, IElementType> =
        TokenType.values().associateWith { IElementType("NEKO_${it.name}", NekoLanguage.INSTANCE) }

    /** The element type that represents [type] in the IDE. */
    fun of(type: TokenType): IElementType = bySyntax.getValue(type)

    val WHITESPACES: TokenSet = TokenSet.create(WHITE_SPACE)
    val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    val STRING_LITERALS: TokenSet = TokenSet.create(of(TokenType.STRING_LITERAL))

    /**
     * The reserved words of Neko, recovered from [TokenType.display].
     *
     * `display` renders a keyword as `'class'` and an operator as `'=='`, so a spelled-out word is
     * exactly a display string that is quoted and whose contents are all letters. That picks up
     * `true` and `false` as well, which is what a reader expects to see highlighted.
     */
    val keywords: Map<String, TokenType> = TokenType.values()
        .mapNotNull { type -> keywordText(type)?.let { it to type } }
        .toMap()

    /** The element types of [keywords], for the highlighter and the completion list. */
    val keywordElementTypes: Set<IElementType> =
        keywords.values.mapTo(mutableSetOf()) { of(it) }

    private fun keywordText(type: TokenType): String? {
        val display = type.display
        if (display.length < 3 || display.first() != '\'' || display.last() != '\'') return null
        val text = display.substring(1, display.length - 1)
        return if (text.isNotEmpty() && text.all { it.isLetter() }) text else null
    }
}
