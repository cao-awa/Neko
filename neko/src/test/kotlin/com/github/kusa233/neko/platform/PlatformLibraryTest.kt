package com.github.kusa233.neko.platform

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for the index of the running JDK's classes.
 *
 * What matters is not the exact list — it belongs to whichever JDK the tests run on — but that the
 * index describes the same platform the analyzer resolves against. If it did not, the IDE would
 * offer names the compiler then rejects, which is the one thing completion must never do.
 */
class PlatformLibraryTest {

    @Test
    fun `java lang classes are indexed`() {
        val classes = PlatformLibrary.javaLangClasses
        assertTrue(classes.isNotEmpty(), "the running JDK should provide java.lang classes")
        assertTrue("String" in classes, "expected String in ${classes.take(10)}...")
        assertTrue("Exception" in classes, "expected Exception in java.lang")
        assertTrue("RuntimeException" in classes, "expected RuntimeException in java.lang")
    }

    @Test
    fun `the index is sorted so a completion list is stable`() {
        val classes = PlatformLibrary.javaLangClasses
        assertTrue(classes == classes.sorted(), "the platform classes should be in a stable order")
    }

    @Test
    fun `nested classes are left out`() {
        // `Outer$Inner` cannot be written in Neko: there is no syntax for naming a nested class, so
        // offering one would offer a name that does not compile.
        assertTrue(
            PlatformLibrary.javaLangClasses.none { it.contains('$') },
            "no simple name should contain '\$'",
        )
    }

    @Test
    fun `a package that is not indexed reports nothing rather than guessing`() {
        assertTrue(PlatformLibrary.classesIn("java.not.a.real.package").isEmpty())
        assertTrue(PlatformLibrary.classesIn("com.example").isEmpty())
    }

    @Test
    fun `a class the analyzer can resolve is in the index`() {
        // `java.lang.RuntimeException` is resolved by the analyzer through the platform, so it has
        // to be one of the names the index offers. This is the agreement that keeps the two halves
        // of the language -- what compiles and what is suggested -- from drifting apart.
        assertTrue("RuntimeException" in PlatformLibrary.javaLangClasses)
    }
}
