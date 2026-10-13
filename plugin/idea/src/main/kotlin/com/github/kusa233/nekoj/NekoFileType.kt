package com.github.kusa233.nekoj

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * The `.neko` file type.
 *
 * Declared as an `object` for two reasons. The platform instantiates a file type by reading a
 * *static field* named by `fieldName` in `plugin.xml`, and a Kotlin `object` provides exactly one,
 * called `INSTANCE`. And `LanguageFileType` refuses to be extended by an anonymous class — it
 * insists the language come from a named class, precisely so that the language id it registers is
 * stable and identifiable in a stack trace.
 *
 * [getName] must agree, character for character, with the `name` attribute of the `fileType`
 * extension: the platform compares them and logs a plugin error when they differ.
 */
object NekoFileType : LanguageFileType(NekoLanguage.INSTANCE) {

    override fun getName(): String = "Neko"

    override fun getDescription(): String = "Neko source file"

    override fun getDefaultExtension(): String = "neko"

    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}
