package com.github.kusa233.neko.analyzer

import com.github.kusa233.neko.source.Span
import com.github.kusa233.neko.symbols.BuiltinFunction
import com.github.kusa233.neko.symbols.FunctionSymbol
import com.github.kusa233.neko.types.BoolType
import com.github.kusa233.neko.types.ByteType
import com.github.kusa233.neko.types.CharType
import com.github.kusa233.neko.types.DoubleType
import com.github.kusa233.neko.types.FloatType
import com.github.kusa233.neko.types.IntType
import com.github.kusa233.neko.types.LongType
import com.github.kusa233.neko.types.NekoType
import com.github.kusa233.neko.types.ObjectType
import com.github.kusa233.neko.types.ShortType
import com.github.kusa233.neko.types.StringType
import com.github.kusa233.neko.types.VoidType

/**
 * Functions supplied by the language itself.
 *
 * Built-ins are resolved before user declarations, so a program can always call `println`. They are
 * modelled as ordinary [FunctionSymbol]s whose [BuiltinFunction] records the JVM method to emit,
 * which is what lets the analyzer and the code generator share one resolution path.
 *
 * `println` is overloaded on every built-in type the language can print. The *Neko* parameter type is
 * what an argument is checked against and the *descriptor* is which JVM method is called, and for
 * three of the overloads those are not the same thing: `java.io.PrintStream` has no `println(byte)`,
 * `println(short)` or `println(char)`, because Java widens a `byte` and a `short` to an `int` at the
 * call site too. So a `byte` argument is declared as a `byte` — so that `println(1)` cannot ambiguously
 * match it — and emitted as `println(int)`, which is the method that exists.
 *
 * Each of the other primitives has a real overload, and reaching it matters: a `long` that fell back
 * to `println(Object)` would be boxed, which is a different method and a different answer for the
 * `null` an `Object` parameter also accepts.
 *
 * The `Object` overload is what lets a program print a value whose static type the compiler does not
 * track — an element of an array parameter, or a `T` erased to `Object`.
 */
object BuiltinLibrary {

    private const val SYSTEM_OUT_RECEIVER = "java/lang/System"

    /** One [FunctionSymbol] per overload, keyed by the name they are called by. */
    fun functions(): Map<String, List<FunctionSymbol>> = linkedMapOf(
        "println" to listOf(
            // Declared as the narrow types, emitted as the `int` overloads `PrintStream` actually has.
            printlnOverload(ByteType, "I"),
            printlnOverload(ShortType, "I"),
            printlnOverload(CharType, "C"),
            printlnOverload(IntType, "I"),
            printlnOverload(LongType, "J"),
            printlnOverload(FloatType, "F"),
            printlnOverload(DoubleType, "D"),
            printlnOverload(BoolType, "Z"),
            printlnOverload(StringType, "Ljava/lang/String;"),
            // Declared last so the primitive overloads win when both would accept the argument:
            // `println(1)` must call `println(int)`, not box and call `println(Object)`.
            printlnOverload(ObjectType, "Ljava/lang/Object;"),
        ),
    )

    private fun printlnOverload(parameterType: NekoType, descriptor: String): FunctionSymbol =
        FunctionSymbol(
            name = "println",
            // Built-ins are not declared in the program text, so they carry an empty span; the
            // renderer recognises it and omits the source snippet.
            span = Span.EMPTY,
            parameterTypes = listOf(parameterType),
            returnType = VoidType,
            enclosingClass = null,
            declaration = null,
            builtin = BuiltinFunction(
                jvmOwner = SYSTEM_OUT_RECEIVER,
                jvmName = "println",
                jvmDescriptor = "($descriptor)V",
                receiverIsSystemOut = true,
            ),
        )
}
