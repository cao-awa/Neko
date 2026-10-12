package com.github.kusa233.neko.types

/**
 * The Neko type model.
 *
 * Types are value-like and compared structurally: two [ArrayType]s are equal exactly when their
 * element types are, and likewise for function types.
 */
sealed interface NekoType {
    /** How the type is spelled in user-facing messages. */
    val displayName: String
}

/** 32-bit signed integer, lowered to the JVM `int`. */
data object IntType : NekoType {
    override val displayName: String get() = "int"
}

/** Immutable UTF-16 string, lowered to `java.lang.String`. */
data object StringType : NekoType {
    override val displayName: String get() = "string"
}

/** Boolean, lowered to the JVM `boolean`. */
data object BoolType : NekoType {
    override val displayName: String get() = "bool"
}

/**
 * `Object` — the erased type of a value whose type the compiler does not track.
 *
 * Neko uses it where the JVM does: a type parameter, the element type of `Object[]`, and the
 * parameter of a method on a class the language did not compile. A value of any class type may be
 * stored as an `Object`, and reading one back needs a cast, which is what the standard library's
 * extractors do.
 */
data object ObjectType : NekoType {
    override val displayName: String get() = "Object"
}

/**
 * `Throwable` — the root of everything `catch` can name.
 *
 * A `catch` clause names a class that extends this, and the analyzer checks that by looking at the
 * resolved class hierarchy rather than at the name.
 */
data object ThrowableType : NekoType {
    override val displayName: String get() = "Throwable"
}

/**
 * The type of an expression that produces no value: a call to a function without a declared return
 * type, or the result of a `println` call.
 */
data object VoidType : NekoType {
    override val displayName: String get() = "void"
}

/**
 * A placeholder used after a type error has already been reported.
 *
 * It compares as compatible with every type so that a single mistake produces a single diagnostic
 * instead of a cascade of follow-on errors.
 */
data object ErrorType : NekoType {
    override val displayName: String get() = "<error>"
}

/** A fixed-length array whose elements all have type [element]. */
data class ArrayType(val element: NekoType) : NekoType {
    override val displayName: String get() = "${element.displayName}[]"
}

/**
 * The type of a lambda: [parameters] is the argument list and [returnType] the type of the value
 * produced by `yield`.
 */
data class FunctionType(
    val parameters: List<NekoType>,
    val returnType: NekoType,
) : NekoType {
    /** Number of parameters this function accepts. */
    val arity: Int get() = parameters.size

    override val displayName: String
        get() = "[" + parameters.joinToString(", ") { it.displayName } + "] -> " + returnType.displayName
}

/**
 * A class type — a class declared in this file, or one that lives outside this compilation unit.
 *
 * Neko has no `new` expression and no constructor *declarations* of its own; [ClassType] exists so
 * that exception values, which are the one kind of object a program may create and throw, have a
 * type. A name that is not declared in the file resolves to an external class in the file's own
 * package, which is how a program refers to a type another file provides.
 */
data class ClassType(
    /** JVM internal name, e.g. `com/github/kusa233/sample/CustomException`. */
    val internalName: String,
    /** Name as written in the source, used in diagnostics. */
    override val displayName: String,
) : NekoType {
    /**
     * The class' name without its package, which is what the analyzer keys declared classes by.
     *
     * `locate com.github.kusa233.sample` puts every class in a package, so the internal name always
     * carries one; a member lookup has to strip it to find the declaration.
     */
    val simpleName: String get() = internalName.substringAfterLast('/')
}

/**
 * Whether a constructor call `Name(...)` may create this type.
 *
 * Every class type is constructible: the compiler emits `new`/`<init>` against the JVM name, and the
 * verifier reports a missing constructor if the class does not actually provide one.
 */
val NekoType.isConstructible: Boolean get() = this is ClassType

/** `true` for the placeholder type produced after an error. */
val NekoType.isError: Boolean get() = this === ErrorType

/**
 * Whether a value of type [this] may be used where [target] is expected.
 *
 * Two leniencies beyond plain equality:
 *
 *  * [ErrorType] is accepted in both directions, so one mistake does not cascade.
 *  * A primitive is assignable to [ObjectType], and every reference type is too. `Object` is what a
 *    type parameter erases to, so a value of *any* type can be stored as one — an `int` arrives
 *    boxed, exactly as it would in Java's generics. That is what makes `Object[]` usable as the
 *    carrier a `raise` boxes its values into.
 */
fun NekoType.isAssignableTo(target: NekoType): Boolean {
    if (this === ErrorType || target === ErrorType) return true
    if (this == target) return true
    if (target === ObjectType) return this !== VoidType
    return false
}

/** Whether this type is represented by a JVM reference, so it can be stored as an `Object`. */
val NekoType.isReferenceType: Boolean
    get() = this is ClassType || this is ArrayType || this is FunctionType || this === StringType ||
        this === ObjectType || this === ThrowableType

/** Whether this type occupies a JVM local-variable slot in category 1 (all Neko types do). */
val NekoType.isValueType: Boolean get() = this !== VoidType && this !== ErrorType

/**
 * Whether a value of this type can be thrown.
 *
 * [ClassType] is the only candidate; whether the class really extends `java/lang/Throwable` is
 * checked at code generation time, where the class hierarchy is available.
 */
val NekoType.isThrowable: Boolean
    get() = this is ClassType || this === ErrorType
