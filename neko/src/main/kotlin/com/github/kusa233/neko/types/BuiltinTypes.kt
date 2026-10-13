package com.github.kusa233.neko.types

/**
 * The built-in types, the names a program may write for them, and the Java type each one is.
 *
 * The table lives here rather than in the analyzer because two very different callers need the same
 * facts. The analyzer resolves a written type name through [byName], so `int` and `Integer` are one
 * type rather than two. The IDE's completion offers the same names, and can only describe them
 * truthfully if it reads the catalogue instead of keeping a second list that could drift from it.
 *
 * The Java reference type is the part worth being explicit about. `int` and `bool` are JVM
 * primitives, so a value of one of those types cannot be stored where an `Object` is expected
 * without being boxed — which is exactly what [referenceTypeNameOf] names, and what the emitter does
 * when it carries a raised value in an `Object[]` or passes one to a generic method. A program may
 * write either spelling: [aliases] records the Java one, so the completion can say so rather than
 * presenting `Integer` as an unrelated type.
 */
object BuiltinTypes {

    /**
     * Every name that resolves to a built-in type, in the order a completion list should show them.
     *
     * The canonical spelling comes first and its Java alias immediately after, so a reader looking
     * for `int` sees `Integer` next to it and learns the connection rather than the other way round.
     */
    val byName: Map<String, NekoType> = linkedMapOf(
        "int" to IntType,
        "Integer" to IntType,
        "string" to StringType,
        "String" to StringType,
        "bool" to BoolType,
        "Boolean" to BoolType,
        "Object" to ObjectType,
        "Throwable" to ThrowableType,
    )

    /** The names that are the Java spelling of another name in [byName], and what they alias. */
    val aliases: Map<String, String> = mapOf(
        "Integer" to "int",
        "String" to "string",
        "Boolean" to "bool",
    )

    /**
     * The Java reference type a value of [type] is carried in once it has to be an `Object`, or
     * `null` when [type] is not one of the built-ins.
     *
     * These are the types the emitter boxes to and casts back from, so naming them here is what lets
     * the IDE show why `Integer` is another way to write `int`.
     */
    fun referenceTypeNameOf(type: NekoType): String? = when (type) {
        IntType -> "java.lang.Integer"
        StringType -> "java.lang.String"
        BoolType -> "java.lang.Boolean"
        ObjectType -> "java.lang.Object"
        ThrowableType -> "java.lang.Throwable"
        else -> null
    }
}
