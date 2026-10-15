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
 *
 * The eight primitive types are Java's own eight, spelled as Neko spells them: `bool` is Java's
 * `boolean`, and every one of them accepts its Java reference type as an alias. What this file also
 * records is the *shape* of each one — its JVM descriptor, the class it boxes to, the arithmetic
 * opcodes it needs — because with eight of them a `when` written in each consumer would be eight
 * places to get the same eight answers wrong.
 */
object BuiltinTypes {

    /**
     * Every name that resolves to a built-in type, in the order a completion list should show them.
     *
     * The canonical spelling comes first and its Java alias immediately after, so a reader looking
     * for `int` sees `Integer` next to it and learns the connection rather than the other way round.
     */
    val byName: Map<String, NekoType> = linkedMapOf(
        "byte" to ByteType,
        "Byte" to ByteType,
        "short" to ShortType,
        "Short" to ShortType,
        "char" to CharType,
        "Character" to CharType,
        "int" to IntType,
        "Integer" to IntType,
        "long" to LongType,
        "Long" to LongType,
        "float" to FloatType,
        "Float" to FloatType,
        "double" to DoubleType,
        "Double" to DoubleType,
        "bool" to BoolType,
        "boolean" to BoolType,
        "Boolean" to BoolType,
        "string" to StringType,
        "String" to StringType,
        "Object" to ObjectType,
        "Throwable" to ThrowableType,
    )

    /** The names that are the Java spelling of another name in [byName], and what they alias. */
    val aliases: Map<String, String> = mapOf(
        "Byte" to "byte",
        "Short" to "short",
        "Character" to "char",
        "Integer" to "int",
        "Long" to "long",
        "Float" to "float",
        "Double" to "double",
        "boolean" to "bool",
        "Boolean" to "bool",
        "String" to "string",
    )

    /**
     * The Java reference type a value of [type] is carried in once it has to be an `Object`, or
     * `null` when [type] is not one of the built-ins.
     *
     * These are the types the emitter boxes to and casts back from, so naming them here is what lets
     * the IDE show why `Integer` is another way to write `int`.
     */
    fun referenceTypeNameOf(type: NekoType): String? = when (val unwrapped = type.unwrapNullable()) {
        ByteType -> "java.lang.Byte"
        ShortType -> "java.lang.Short"
        CharType -> "java.lang.Character"
        IntType -> "java.lang.Integer"
        LongType -> "java.lang.Long"
        FloatType -> "java.lang.Float"
        DoubleType -> "java.lang.Double"
        BoolType -> "java.lang.Boolean"
        StringType -> "java.lang.String"
        ObjectType -> "java.lang.Object"
        ThrowableType -> "java.lang.Throwable"
        else -> null
    }

    /**
     * The reference type [type] boxes to, as a JVM internal name — `java/lang/Integer` — or `null`
     * when [type] is not a built-in.
     *
     * Separate from [referenceTypeNameOf] because the two callers need different spellings of the
     * same fact: the source-level one carries dots, and a `visitMethodInsn` needs slashes.
     */
    fun boxedInternalNameOf(type: NekoType): String? =
        referenceTypeNameOf(type)?.replace('.', '/')

    /**
     * The eight primitive types, in the order Java's own documentation lists them.
     *
     * `bool` counts as one of them even though arithmetic never applies to it: it *is* a JVM
     * primitive that occupies a slot and boxes to `java.lang.Boolean`, and code that enumerates the
     * primitives to decide how to carry a value has to include it.
     */
    val primitives: List<NekoType> = listOf(
        ByteType,
        ShortType,
        CharType,
        IntType,
        LongType,
        FloatType,
        DoubleType,
        BoolType,
    )

    /**
     * Whether [type] is one of the eight primitives, so it boxes to a reference type.
     *
     * A nullable type is deliberately *not* one, even when its inner type is: `int?` is carried as a
     * `java.lang.Integer` so that `null` can be stored in it, and every caller here is asking "is the
     * value on the stack a JVM primitive right now?" — the question a `cmp` or an `IF_ICMPEQ` depends
     * on. Unwrapping would answer yes for a value that is a reference, which is exactly the mistake
     * this predicate exists to prevent.
     */
    fun isPrimitive(type: NekoType): Boolean = type in primitives

    /**
     * Whether [type] is one of the seven numeric primitives, i.e. a type arithmetic and ordering are
     * defined on.
     *
     * `bool` is deliberately not one of them: it is a primitive, but `true + true` is not a value the
     * language has an answer for.
     */
    fun isNumeric(type: NekoType): Boolean = type in numericTypes

    /** The numeric types, i.e. the primitives arithmetic and ordering are defined on. */
    private val numericTypes: Set<NekoType> = setOf(
        ByteType,
        ShortType,
        CharType,
        IntType,
        LongType,
        FloatType,
        DoubleType,
    )

    /**
     * How many JVM local-variable slots a value of [type] occupies: two for `long` and `double`, one
     * for everything else.
     *
     * This is the fact that is easiest to get silently wrong, because a slot that is one too small
     * still produces a class file — it just holds half a `long`, and the next local reads garbage.
     *
     * A nullable type is one slot whatever it wraps, for the same reason it is not a primitive: a
     * `long?` is a `java.lang.Long`, which is a reference and never straddles two slots.
     */
    fun slotCountOf(type: NekoType): Int = if (type == LongType || type == DoubleType) 2 else 1

    /**
     * The inclusive range of integral [type], or `null` for a type that has none.
     *
     * `char` is unsigned, so its range runs from zero rather than from a JVM `short`'s minus
     * thirty-odd thousand; that is the whole reason it cannot simply be treated as `short`.
     */
    fun integralRangeOf(type: NekoType): LongRange? = when (type) {
        ByteType -> Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
        ShortType -> Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
        CharType -> 0L..0xFFFFL
        IntType -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        LongType -> Long.MIN_VALUE..Long.MAX_VALUE
        else -> null
    }

    /**
     * The finite range of the floating-point [type], or `null` for a type that has none.
     *
     * The bounds are the *finite* extremes rather than the maximum representable value, because a
     * literal beyond them rounds to infinity — a value the language has no spelling for, so it is
     * reported instead of being quietly produced.
     */
    fun floatingRangeOf(type: NekoType): ClosedFloatingPointRange<Double>? = when (type) {
        FloatType -> (-Float.MAX_VALUE.toDouble())..Float.MAX_VALUE.toDouble()
        DoubleType -> (-Double.MAX_VALUE)..Double.MAX_VALUE
        else -> null
    }
}
