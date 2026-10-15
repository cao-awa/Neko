package com.github.kusa233.neko.platform

import java.lang.module.ModuleFinder
import java.lang.module.ModuleReader
import java.util.TreeMap

/**
 * The classes the Java platform provides, as the compiler is prepared to let a program name them.
 *
 * A Neko program may name a class it does not declare: an unqualified capitalized name is looked up
 * in `java.lang` first, and otherwise in the file's own `import`ed package. That rule is why this
 * index exists — the IDE cannot offer those names unless something can say what they are, and the
 * compiler already has the only answer that matters, which is the JDK it is running on.
 *
 * The list is read from the running JDK's own modules rather than kept as a table, for the same
 * reason the analyzer asks the platform whether a class exists instead of carrying a list: a JDK
 * gains and loses classes between releases, and a table would be wrong the moment it was written.
 *
 * Only `java.base` is indexed. It holds `java.lang` and `java.util` and most of what a program
 * reaches for, and every module is another scan; `java.base` is the one whose absence would make the
 * index useless, so it is the one worth paying for.
 */
object PlatformLibrary {

    /** The module whose packages are indexed. */
    private const val BASE_MODULE = "java.base"

    /**
     * Simple class names by the package that holds them, e.g. `java.lang` to `String`.
     *
     * Computed once, on first use. Reading it takes a few milliseconds, which is far too slow to
     * repeat while someone is typing but nothing at all as a one-off — hence the laziness, which
     * also keeps the cost off the path of a program that never asks for completion.
     */
    private val classesByPackage: Map<String, List<String>> by lazy { readPlatformClasses() }

    /**
     * The classes of `java.lang`, which a program may name without an import.
     *
     * Sorted, so a completion list built from it is stable between runs.
     */
    val javaLangClasses: List<String> get() = classesByPackage["java.lang"].orEmpty()

    /**
     * The classes of [packageName], which a program may name when the file imports that package.
     *
     * Empty for a package outside the indexed module, and for one the JDK does not have.
     */
    fun classesIn(packageName: String): List<String> = classesByPackage[packageName].orEmpty()

    private fun readPlatformClasses(): Map<String, List<String>> {
        val found = TreeMap<String, MutableList<String>>()
        try {
            val module = ModuleFinder.ofSystem().find(BASE_MODULE).orElse(null) ?: return emptyMap()
            module.open().use { reader -> collect(reader, found) }
        } catch (error: RuntimeException) {
            // A platform that cannot describe itself -- a stripped runtime, or one whose module
            // image is not a directory. Completion simply has no platform classes to offer, which is
            // a smaller loss than failing the completion it was asked for.
            return emptyMap()
        }
        return found.mapValues { (_, names) -> names.sorted() }
    }

    private fun collect(reader: ModuleReader, into: MutableMap<String, MutableList<String>>) {
        // `reader.list()` yields resource paths such as `java/lang/String.class`. A nested class is
        // `Outer$Inner.class`, and Neko has no syntax for naming one, so those are skipped rather
        // than offered as names that could not be written.
        reader.list()
            .filter { it.endsWith(".class") && !it.contains('$') }
            .forEach { resource ->
                val path = resource.removeSuffix(".class")
                val separator = path.lastIndexOf('/')
                if (separator <= 0) return@forEach
                val packageName = path.substring(0, separator).replace('/', '.')
                into.getOrPut(packageName) { mutableListOf() } += path.substring(separator + 1)
            }
    }
}
