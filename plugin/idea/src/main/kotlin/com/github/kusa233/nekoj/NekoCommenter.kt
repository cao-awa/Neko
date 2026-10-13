package com.github.kusa233.nekoj

import com.intellij.lang.Commenter

/**
 * Tells the IDE how to comment code out, which is what Ctrl-/ and Ctrl-Shift-/ use.
 *
 * Neko accepts exactly the two forms the compiler's lexer knows: `//` to end of line, and `/* */`.
 * The "commented block comment" answers describe what the markers become when a block that is
 * already commented gets commented again — the same markers, since Neko's block comments do not
 * nest and adding a second pair would just move the end of the comment outward.
 */
class NekoCommenter : Commenter {

    override fun getLineCommentPrefix(): String = "//"

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String = "/*"

    override fun getCommentedBlockCommentSuffix(): String = "*/"
}
