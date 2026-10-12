package com.github.kusa233.test

/**
 * Test fixtures for Neko programs that refer to classes they cannot declare.
 *
 * Neko has no inheritance syntax, so a program that throws an exception names a class supplied
 * from outside the language. The compiler resolves such a name against the source file's own
 * package, and the loaded test programs resolve it against these classes.
 */
class CustomException : Exception("custom exception")

/** The second exception type, used to check that a `throws` clause can list more than one. */
class OtherException : Exception("other exception")
