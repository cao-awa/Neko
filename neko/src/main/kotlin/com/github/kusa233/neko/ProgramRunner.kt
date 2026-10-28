package com.github.kusa233.neko

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Runs the `main` of a class produced by one compilation.
 *
 * The classes live in [CompilationResult.classes] as bytes, so they are defined by
 * [CompiledClassLoader] rather than loaded from a directory. That keeps `--run` independent of
 * `--output`: a reader may try a program without asking for class files, and no stale copy on disk can
 * quietly answer instead of the code that was just compiled. The compiler's own loader is the parent,
 * which supplies the JDK and the few classes the compiler itself ships (ASM, the Kotlin runtime); every
 * Neko class, including the standard library, comes from the map.
 *
 * A program's `main` is the JVM entry point, so it is looked up as exactly `static main(String[])`.
 * Neko lets an argument-less `main()` be written, and the code generator emits the `String[]` bridge
 * the JVM looks for, so both spellings arrive here as the same method.
 */
internal object ProgramRunner {

    /** What happened when [run] was asked to execute a class. */
    sealed interface Outcome {
        /** `main` was found and returned normally. */
        data object Returned : Outcome

        /** No class of that name was part of the compilation. */
        data class MissingClass(val name: String) : Outcome

        /** The class exists but has no `static main(String[])`. */
        data class MissingMain(val name: String) : Outcome

        /** The class or its `main` failed, including while the class was being initialised. */
        data class Failed(val name: String, val cause: Throwable) : Outcome
    }

    /**
     * Runs [className]'s `main` with no arguments.
     *
     * [className] is the JVM name in dot form, which is what a reader writes: `locate neko.sample`
     * plus `class TestClass` is `neko.sample.TestClass`. A failure to initialise the class and a
     * failure inside `main` are both [Outcome.Failed]; the distinction a caller needs is that the
     * program did not finish, not which of the two places raised.
     */
    fun run(classes: Map<String, ByteArray>, className: String): Outcome {
        val loader = CompiledClassLoader(classes)
        val type = try {
            // Initialising here as well as at invocation makes a failing static initialiser a
            // `Failed` outcome of the class named on the command line, not a link error surfacing
            // from inside `invoke`.
            Class.forName(className, true, loader)
        } catch (missing: ClassNotFoundException) {
            return Outcome.MissingClass(className)
        } catch (failure: Throwable) {
            return Outcome.Failed(className, failure)
        }

        val main = try {
            type.getMethod("main", Array<String>::class.java)
        } catch (missing: NoSuchMethodException) {
            return Outcome.MissingMain(className)
        }
        if (!Modifier.isStatic(main.modifiers)) return Outcome.MissingMain(className)

        return try {
            main.invoke(null, emptyArray<String>())
            Outcome.Returned
        } catch (wrapped: InvocationTargetException) {
            // The reflection wrapper is not the program's exception; report the one `main` threw.
            Outcome.Failed(className, wrapped.targetException ?: wrapped)
        } catch (failure: Throwable) {
            Outcome.Failed(className, failure)
        }
    }

    /**
     * Defines the compiled classes in memory, delegating everything else to [parent].
     *
     * Compiled classes are resolved *before* the parent, which is the one place this loader departs
     * from the usual parent-first rule. The parent is the compiler's own classpath, and a Neko program
     * is free to declare a class whose name looks like one of the compiler's; parent-first would then
     * run the compiler's class instead of the one just compiled, silently and with no diagnostic. The
     * JDK — the only other source of classes — is never in the map, so it is unaffected.
     */
    private class CompiledClassLoader(
        private val classes: Map<String, ByteArray>,
        parent: ClassLoader = ProgramRunner::class.java.classLoader,
    ) : ClassLoader(parent) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            synchronized(getClassLoadingLock(name)) {
                var type = findLoadedClass(name)
                if (type == null) {
                    type = defineIfCompiled(name) ?: super.loadClass(name, false)
                }
                if (resolve) resolveClass(type)
                type
            }

        override fun findClass(name: String): Class<*> =
            defineIfCompiled(name) ?: throw ClassNotFoundException(name)

        /** The class named [name] if this compilation produced it, otherwise `null`. */
        private fun defineIfCompiled(name: String): Class<*>? {
            val bytes = classes[name.replace('.', '/')] ?: return null
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}
