package com.github.kusa233.neko.source

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The Neko standard library, as the compiler carries it.
 *
 * The library is written in Neko, which means it is *compiled* rather than linked: a program that
 * implements `Iterable`, or that uses `scratch`/`nyan`/`yield`, needs the declarations to be in the
 * same program. So every compilation starts with these sources, and the program's own files follow.
 *
 * **Why the sources ship inside the compiler.** The alternative — reading `stdlib/` from the repository
 * at run time — works only where that directory happens to be, which rules out a packaged jar, the
 * IDE plugin, and a test run from any other directory. The build copies the `.neko` files into the jar
 * as resources (see `build.gradle.kts`), and this object reads them back by a path that is the same
 * whether they came from a jar or from a directory of class files.
 *
 * A program may still declare a class the library also declares. Its own declaration wins, because it
 * is parsed later and shadowing is resolved by order — the library is a floor, not a ceiling.
 */
object StandardLibrary {

    /**
     * Where the sources live, relative to the classpath root.
     *
     * A single constant because three things have to agree on it: the build that copies the files in,
     * the resource lookup below, and the jar's own layout. `neko/stdlib/` is a package-ish path rather
     * than a bare `stdlib/` so that the resource cannot collide with a resource of that name belonging
     * to something else on the classpath.
     */
    const val RESOURCE_ROOT = "neko/stdlib"

    /**
     * The library's sources, sorted by path so a compilation is reproducible.
     *
     * Sorted because the analyzer resolves a name against the first declaration it meets; letting a
     * directory enumeration decide would make the same program mean different things on different
     * runs. A missing resource is reported as an empty list rather than thrown, because the one caller
     * that must not fail is the IDE: a broken installation should leave the editor without library
     * classes, not without highlighting.
     */
    fun sources(): List<SourceFile> = load().map { (name, content) ->
        SourceFile(name = name.substringAfterLast('/'), content = content)
    }

    /**
     * The library's sources read from [directory], for a reader who keeps their own.
     *
     * This is what `--stdlib-path` uses. The directory is walked recursively, because a library is a
     * set of packages and a package is a folder — `com/github/kusa233/neko/stdlib/emitter/` — so
     * requiring them all to sit flat would make the option useless for anything but a toy.
     *
     * Each source keeps its [SourceFile.path], unlike the packaged ones. That is the honest
     * difference between the two: these files really exist on disk, so a diagnostic about one can
     * name the file and line the reader has to open. The packaged copy has no path because there is
     * no file behind it.
     *
     * Sorted by path for the same reason as [sources]: resolution takes the first declaration it
     * meets, so a directory enumeration must not decide what a program means.
     *
     * An empty result is *not* an error here — this function reports nothing. The caller knows
     * whether it was reading a library or merely probing, and only the command line has a reader to
     * tell that the directory was empty.
     */
    fun sourcesIn(directory: Path): List<SourceFile> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".neko") }
                .sorted()
                .map { path ->
                    SourceFile(
                        name = path.fileName.toString(),
                        content = Files.readString(path),
                        path = path,
                    )
                }
                .toList()
        }
    }

    /**
     * The library's sources as `path -> text`, empty when the resource root cannot be read.
     *
     * **Two lookups, in this order.** A bare `getResource("neko/stdlib")` answers only when the
     * classpath entry is an exploded *directory* — a Gradle test run, say. Inside a jar the same call
     * returns `null`, because a jar has no entries for directories unless they were written as such,
     * and even then the URL is not something that can be opened and walked. So the directory lookup is
     * tried first and the jar is read directly as a fallback.
     *
     * The jar is found from a *file* resource rather than from the directory: `getResource` on
     * `neko/stdlib/...` cannot be asked for a directory, but it can always be asked for the class this
     * object is loaded from, which is guaranteed to be in the same jar the resources were packaged
     * into. That is what makes the plugin module work, where the compiler arrives as a jar on the
     * plugin's classpath and the resources are inside it.
     */
    private fun load(): List<Pair<String, String>> = runCatching { read() }.getOrDefault(emptyList())

    private fun read(): List<Pair<String, String>> {
        val loader = StandardLibrary::class.java.classLoader
        loader.getResource(RESOURCE_ROOT)?.let { url ->
            return if (url.protocol == "jar") readFromJar(url) else readFromDirectory(url)
        }
        // No directory on the classpath: the library is packaged inside the jar this class came from.
        val self = StandardLibrary::class.java.protectionDomain?.codeSource?.location
            ?: loader.getResource(StandardLibrary::class.java.name.replace('.', '/') + ".class")
            ?: return emptyList()
        return readFromJar(self)
    }

    /**
     * Reads the library out of a directory of class files, which is how a test run and an IDE see it.
     *
     * The root of a `file:` URL is the `neko/stdlib` directory itself, so only its contents are walked:
     * the package path is already behind us.
     */
    private fun readFromDirectory(url: URL): List<Pair<String, String>> {
        val root = Path.of(url.toURI())
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".neko") }
                .map { path ->
                    val relative = root.relativize(path).toString().replace('\\', '/')
                    "$RESOURCE_ROOT/$relative" to Files.readString(path)
                }
                .sorted(compareBy { it.first })
                .toList()
        }
    }

    /**
     * Reads the library out of the jar the compiler was loaded from.
     *
     * The [location] arrives in one of two shapes, and both have to reduce to a plain file path:
     *
     *  * `jar:file:/…/neko.jar!/neko/stdlib` — a resource URL into a jar. The `jar:` scheme has to be
     *    stripped *before* the `file:` one, which is why the prefix is removed in that order.
     *  * `file:/…/neko.jar` — a code-source location, already the jar itself.
     *
     * The zip is opened directly rather than through a `JarURLConnection`, which hands out one entry at
     * a time while it is the entry *names* that say which files are there. `use` closes it again: a
     * leaked handle on the running jar would stop it being replaced on an upgrade.
     *
     * Only entries under [RESOURCE_ROOT] are taken, so a jar that happens to hold other `.neko` files
     * — a test fixture, say — does not quietly add them to every program.
     */
    private fun readFromJar(location: URL): List<Pair<String, String>> {
        val text = location.toString()
        val jarUrl = text.substringBefore("!/").removePrefix("jar:")
        val path = Path.of(java.net.URI(jarUrl))

        return ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory &&
                        entry.name.endsWith(".neko") &&
                        entry.name.startsWith("$RESOURCE_ROOT/")
                }
                .sortedBy { entry -> entry.name }
                .map { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes().decodeToString() } }
                .toList()
        }
    }
}
