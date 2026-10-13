package com.github.kusa233.nekoj

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

/**
 * The PSI file for a `.neko` source file.
 *
 * [PsiFileBase] supplies everything a file needs except its type, so this class is deliberately
 * three lines long: it exists to bind the file to [NekoLanguage] and [NekoFileType], which is what
 * makes the platform route highlighting, annotation and completion here instead of treating the
 * file as plain text.
 */
class NekoPsiFile(viewProvider: FileViewProvider) :
    PsiFileBase(viewProvider, NekoLanguage.INSTANCE) {

    override fun getFileType(): FileType = NekoFileType

    override fun toString(): String = "NekoFile(${name})"
}
