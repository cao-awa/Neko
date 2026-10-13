package com.github.kusa233.nekoj

import com.intellij.lang.Language

/**
 * The Neko language, as far as the IDE is concerned.
 *
 * There is no `language` extension point in the platform: a [Language] registers *itself*, in its
 * own constructor, under the id passed here. That is why this class looks like an empty subclass —
 * the interesting work happens in `Language`'s constructor the first time an instance is built.
 *
 * Instances are also not free. Registering the same class twice throws an
 * `ImplementationConflictException`, so exactly one instance may ever exist; [INSTANCE] is it, and
 * every other part of the plugin refers to that rather than to `NekoLanguage()`.
 *
 * The instance comes into existence when [NekoFileType] is instantiated, which the platform does
 * eagerly to register the `.neko` extension. By the time any `language="Neko"` extension is looked
 * up, the id is therefore already registered.
 */
class NekoLanguage private constructor() : Language(ID) {
    companion object {
        /** The language id, which is also the `language="..."` value in `plugin.xml`. */
        const val ID = "Neko"

        /** The single instance; constructing another would fail. */
        @JvmField
        val INSTANCE = NekoLanguage()
    }
}
