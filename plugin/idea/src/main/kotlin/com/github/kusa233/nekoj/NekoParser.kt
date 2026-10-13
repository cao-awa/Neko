package com.github.kusa233.nekoj

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/**
 * Builds the IDE's syntax tree out of the lexer's tokens.
 *
 * The tree is flat on purpose. Reconstructing the compiler's grammar here would mean a second
 * parser, free to disagree with the real one — and when the two disagree it is always the IDE that
 * is wrong. Instead this parser only has to be *total*: it accepts any token sequence, including
 * half-typed text and text with syntax errors, and never throws or gets stuck. The structure the
 * IDE actually reports on comes from the compiler itself, which the annotator runs over the file.
 *
 * A flat tree still gives the editor everything token-level: colours, comment folds, matching
 * braces, and correct handling of whitespace and comments, which the platform turns into
 * [com.intellij.psi.PsiWhiteSpace] and [com.intellij.psi.PsiComment] because
 * [NekoParserDefinition] names those token sets.
 *
 * The one piece of structure it does build is the root element, and the marker around it is not
 * decorative: a parser that advances the lexer without ever marking anything makes the platform
 * log `Parser produced no markers` and fail the file. Marking the whole file is also what gives the
 * tree a proper root node for the file element type to hang off.
 */
class NekoParser : PsiParser {

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        file.done(root)
        return builder.treeBuilt
    }
}
