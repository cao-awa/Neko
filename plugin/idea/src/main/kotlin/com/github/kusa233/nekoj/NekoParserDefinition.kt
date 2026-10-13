package com.github.kusa233.nekoj

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Wires the Neko language into the platform's parsing machinery.
 *
 * This class is not optional. The platform decides a file's language by looking for a
 * [ParserDefinition] for it, and a file type whose language has none is treated as *plain text*:
 * the PSI is a `PsiPlainTextFileImpl`, the syntax highlighter is never asked for colours, and the
 * annotator and completion contributor are never consulted at all. Every other extension in
 * `plugin.xml` therefore depends on this one.
 *
 * It is also where whitespace and comments are declared. The platform reads these token sets to
 * decide which leaves become [com.intellij.psi.PsiWhiteSpace] and [com.intellij.psi.PsiComment],
 * and [com.intellij.lang.ASTFactory] consults the same sets when it builds leaves — which is why
 * declaring them is enough to get real comment PSI without a custom AST factory.
 */
class NekoParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = NekoHighlightingLexer()

    override fun createParser(project: Project?): PsiParser = NekoParser()

    override fun getFileNodeType(): IFileElementType = NekoTokens.FILE

    override fun getWhitespaceTokens(): TokenSet = NekoTokens.WHITESPACES

    override fun getCommentTokens(): TokenSet = NekoTokens.COMMENTS

    override fun getStringLiteralElements(): TokenSet = NekoTokens.STRING_LITERALS

    /**
     * Wraps a composite node in a plain PSI element.
     *
     * The tree is flat, so in practice this is reached rarely; leaves are created by the platform
     * directly, as whitespace, comments or plain leaves.
     */
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = NekoPsiFile(viewProvider)
}
