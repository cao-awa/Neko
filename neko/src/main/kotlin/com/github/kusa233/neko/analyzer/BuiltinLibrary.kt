package com.github.kusa233.neko.analyzer

import com.github.kusa233.neko.source.Span
import com.github.kusa233.neko.symbols.BuiltinFunction
import com.github.kusa233.neko.symbols.FunctionSymbol
import com.github.kusa233.neko.types.NekoType
import com.github.kusa233.neko.types.BoolType
import com.github.kusa233.neko.types.IntType
import com.github.kusa233.neko.types.ObjectType
import com.github.kusa233.neko.types.StringType
import com.github.kusa233.neko.types.VoidType

/**
 * Functions supplied by the language itself.
 *
 * Built-ins are resolved before user declarations, so a program can always call `println`. They are
 * modelled as ordinary [FunctionSymbol]s whose [BuiltinFunction] records the JVM method to emit,
 * which is what lets the analyzer and the code generator share one resolution path.
 *
 * `println` is overloaded on `int`, `string`, `bool` and `Object`, mirroring `System.out.println`.
 * The `Object` overload is what lets a program print a value whose static type the compiler does not
 * track — an element of the `Object[]` a `raise` carried, or a `T` erased to `Object`.
 */
object BuiltinLibrary {

    private const val SYSTEM_OUT_RECEIVER = "java/lang/System"

    /** One [FunctionSymbol] per overload, keyed by the name they are called by. */
    fun functions(): Map<String, List<FunctionSymbol>> = linkedMapOf(
        "println" to listOf(
            printlnOverload(IntType, "I"),
            printlnOverload(StringType, "Ljava/lang/String;"),
            printlnOverload(BoolType, "Z"),
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
