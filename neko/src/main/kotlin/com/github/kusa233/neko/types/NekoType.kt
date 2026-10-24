package com.github.kusa233.neko.types

/**
 * The Neko type model.
 *
 * Types are value-like and compared structurally: two [ArrayType]s are equal exactly when their
 * element types are, and likewise for function types.
 *
 * The primitive types are Java's eight, and they are eight *distinct* types rather than points on a
 * numeric lattice. That is the language's rule and not an accident of the implementation: Neko does
 * no implicit conversion between named types at all — `int + string` is an error — so `long` is not
 * a wider `int` to convert into, it is a different type that happens to be spelled similarly. A
 * literal is the one exception, because it has no type of its own until its context gives it one.
 */
sealed interface NekoType {
    /** How the type is spelled in user-facing messages. */
    val displayName: String
}

/** 8-bit signed integer, lowered to the JVM `byte`. */
data object ByteType : NekoType {
    override val displayName: String get() = "byte"
}

/** 16-bit signed integer, lowered to the JVM `short`. */
data object ShortType : NekoType {
    override val displayName: String get() = "short"
}

/**
 * 16-bit unsigned integer, lowered to the JVM `char`.
 *
 * Unsigned is the property that makes `char` its own type rather than a `short` under another name:
 * `0xFFFF` is a legal `char` and an illegal `short`, and `char` arithmetic wraps at 65535 rather
 * than at 32767.
 */
data object CharType : NekoType {
    override val displayName: String get() = "char"
}

/** 32-bit signed integer, lowered to the JVM `int`. */
data object IntType : NekoType {
    override val displayName: String get() = "int"
}

/**
 * 64-bit signed integer, lowered to the JVM `long`.
 *
 * A `long` is the first type in the language that occupies *two* local-variable slots, which is a
 * fact every pass that hands out slots has to know about; see `BuiltinTypes.slotCountOf`.
 */
data object LongType : NekoType {
    override val displayName: String get() = "long"
}

/** 32-bit floating point, lowered to the JVM `float`. */
data object FloatType : NekoType {
    override val displayName: String get() = "float"
}

/** 64-bit floating point, lowered to the JVM `double`. */
data object DoubleType : NekoType {
    override val displayName: String get() = "double"
}

/** Boolean, lowered to the JVM `boolean`. */
data object BoolType : NekoType {
    override val displayName: String get() = "bool"
}

/** Immutable UTF-16 string, lowered to `java.lang.String`. */
data object StringType : NekoType {
    override val displayName: String get() = "string"
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
 * `Throwable` — the type of anything the JVM can throw, and what a `try` handler catches.
 *
 * Nothing in the language names it as a catch type any more: the `try`/`catch` statement was removed.
 * It survives as the declared type of a throwable that crosses a frame boundary — the standard
 * library's carrier for `raise` is declared as one — and as the erased type of the local a generated
 * handler stores its exception in.
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
 * A type that additionally admits `null` — written `T?` in the source.
 *
 * This is the *only* type `null` may be assigned to, and the wrapper is what records that: a plain
 * `string` and a `string?` are different types, so a program that hands a `string?` to something
 * wanting a `string` is rejected rather than failing at run time. A nullable value is therefore
 * always a reference — for a primitive [inner], that means the boxed type, because a JVM `int` slot
 * has no room for `null` at all.
 *
 * A nullable value is deliberately *not* usable for much: it may be assigned, compared against
 * `null` with `==`/`!=`, passed along as another nullable type, or dereferenced through `?.`. Every
 * other use — reading a field, calling a function, indexing — is a compile-time error, because those
 * are exactly the operations that would otherwise raise a null-pointer exception. Once a value has
 * been narrowed to `T`, the operations of `T` become available again.
 */
data class NullableType(val inner: NekoType) : NekoType {
    /**
     * The inner name with `?` appended — parenthesised when the inner type is a function.
     *
     * A function type ends in its return type, so `[int] -> string?` already means "returns a
     * `string?`"; appending a bare `?` to the whole of it would read the same way while meaning "the
     * function itself may be absent". The parentheses are not decoration: without them the two
     * different types have one spelling, and a diagnostic quoting it back would describe the wrong
     * one.
     */
    override val displayName: String
        get() = if (inner is FunctionType) "(${inner.displayName})?" else "${inner.displayName}?"
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
    /**
     * The type arguments written on a use of this type: the `<string>` in `Iterable<string>`.
     *
     * Erasure means the JVM never sees these, and member lookup never consults them — a `T` parameter
     * is `Object` at run time whatever was written. They are kept anyway because `for ... in` has to
     * know what an iterable yields *before* generating code: the loop variable's type is checked
     * against the argument, and `next()` is unboxed into it. That check is the only place the argument
     * is read, which is why it can be a bare list here rather than a full generic type system.
     *
     * Empty for a use that wrote none, and for every type that is not generic.
     */
    val arguments: List<NekoType> = emptyList(),
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
 *    type parameter erases to, so a value of *any* type can be stored as one — an `int` or a `long`
 *    arrives boxed, exactly as it would in Java's generics. That is what makes `Object[]` usable as
 *    the carrier a `raise` boxes its values into.
 *
 * Nothing else is: in particular two different numeric primitives are not assignable to each other
 * in either direction, which is what makes `x: long = someInt` an error. A *literal* is the one thing
 * that may be read as a different type, and that happens in the analyzer, where the literal's value
 * is visible — a value that already has a type is never converted.
 *
 * Nullability is asymmetric, and deliberately so:
 *
 *  * A `T` may be used where `T?` is expected — the value is already not null, so the wider type
 *    costs nothing. This is what makes a nullable parameter usable without ceremony.
 *  * A `T?` may *not* be used where `T` is expected. That direction is the one that would need a
 *    run-time check the language refuses to perform implicitly, so it is a compile-time error.
 */
fun NekoType.isAssignableTo(target: NekoType): Boolean {
    if (this === ErrorType || target === ErrorType) return true
    if (this == target) return true
    if (target is NullableType) {
        // Both nullable: the inner types decide. A non-null value: it fits if its own type does,
        // which is the "non-null to nullable is allowed" direction.
        return if (this is NullableType) inner.isAssignableTo(target.inner) else isAssignableTo(target.inner)
    }
    if (target === ObjectType) return this !== VoidType
    // A nullable value never fits a non-nullable type; the caller must narrow it first.
    if (this is NullableType) return false
    return false
}

/**
 * Whether this type is represented by a JVM reference, so it can be stored as an `Object`.
 *
 * Every primitive is excluded, which is the point of asking: a `long` cannot be stored as an `Object`
 * until it has been boxed, and a pass that treats it as if it could will emit an `AASTORE` of a
 * two-slot value.
 */
val NekoType.isReferenceType: Boolean
    get() = this is ClassType || this is ArrayType || this is FunctionType || this === StringType ||
        this === ObjectType || this === ThrowableType || this is NullableType

/**
 * Whether this type admits `null`.
 *
 * The question a caller means is "may I assign `null` here?" — and for anything but a [NullableType]
 * the answer is no, which is what makes `x: string = null` a compile-time error rather than a
 * run-time surprise.
 */
val NekoType.isNullable: Boolean get() = this is NullableType

/**
 * [this] with its [NullableType] wrapper removed, e.g. `int?` becomes `int`; anything else is itself.
 *
 * The question a caller means is "what is this a nullable *of*?". That is what a pass working in
 * terms of the JVM's representation needs: an `int?` is stored as a `java.lang.Integer`, and the
 * wrapper names a reference, so naming that box means asking about the inner type.
 *
 * One unwrapping is always enough: the parser rejects a second `?` on the same type, so `int??`
 * cannot reach the analyzer and there is never more than one wrapper to strip.
 */
fun NekoType.unwrapNullable(): NekoType = if (this is NullableType) inner else this

/**
 * Whether this type occupies a JVM local-variable slot and so can be a local's type.
 *
 * A `long` or a `double` occupies *two* slots; that is a question about how many, not whether, and
 * `BuiltinTypes.slotCountOf` answers it.
 */
val NekoType.isValueType: Boolean get() = this !== VoidType && this !== ErrorType

/**
 * Whether a value of this type can be thrown.
 *
 * [ClassType] is the only candidate; whether the class really extends `java/lang/Throwable` is
 * checked at code generation time, where the class hierarchy is available.
 */
val NekoType.isThrowable: Boolean
    get() = this is ClassType || this === ErrorType
