package com.github.kusa233.neko.symbols

import com.github.kusa233.neko.ast.ConstructorDeclaration
import com.github.kusa233.neko.ast.FunctionDeclaration
import com.github.kusa233.neko.ast.VariableKind
import com.github.kusa233.neko.source.Span
import com.github.kusa233.neko.types.NekoType
import com.github.kusa233.neko.types.ClassType
import com.github.kusa233.neko.types.FunctionType

/**
 * A named entity the analyzer resolved a [com.github.kusa233.neko.ast.NameExpression] to.
 *
 * Symbols are produced only by the analyzer; the code generator reads them back through the
 * `resolution` / `symbol` fields the analyzer filled in on the AST nodes, so it never has to
 * re-run name resolution.
 */
sealed interface Symbol {
    val name: String

    /** The type of the value this symbol denotes. */
    val type: NekoType

    /** Where the symbol was introduced; used for "previous declaration is here" notes. */
    val span: Span
}

/** A class or interface declared in the source file. */
class ClassSymbol(
    override val name: String,
    override val span: Span,
    /** JVM internal name, e.g. `com/github/kusa233/sample/Sample`. */
    val jvmInternalName: String,
    /**
     * The package prefix of the file that declared this class, in JVM internal form.
     *
     * Each file may `locate` its own package, so a class' package is a property of the declaration
     * rather than of the compilation: the classes one file names live in that file's package, not in
     * the package of whichever file happened to be compiled first.
     */
    val packagePrefix: String = "",
    /** `true` when the declaration was written `interface`. */
    val isInterface: Boolean = false,
    /**
     * `true` when the declaration was written `meow class`, i.e. its `equals` and `hashCode` are
     * derived from its fields.
     *
     * The declaration itself records this; the symbol carries it too because code generation decides
     * from the resolved *type* whether `==` dispatches to a derived `equals`, and a type names a
     * symbol rather than the declaration it came from.
     */
    val isMeow: Boolean = false,
    /**
     * `true` when the declaration was written `miao class`, i.e. it is an enum.
     *
     * Carried on the symbol as well as the declaration because code generation asks questions of the
     * resolved *type* — how `==` compares two values, how a constant is read — and a type names a
     * symbol rather than the declaration behind it.
     */
    val isMiao: Boolean = false,
    /**
     * Type parameter names, erased to `Object`.
     *
     * A parameter name is accepted anywhere a type may be written inside the declaring class, and
     * resolves to `Object`: the JVM erases it, and Neko never needs to instantiate one.
     */
    val typeParameters: List<String> = emptyList(),
) : Symbol {
    override val type: NekoType
        get() = error("a class is not a first-class value in Neko yet")

    /** Fields by name; the analyzer rejects duplicates before this is populated. */
    val fields: MutableMap<String, FieldSymbol> = LinkedHashMap()

    /** Functions by name; several entries mean the function is overloaded. */
    val functions: MutableMap<String, MutableList<FunctionSymbol>> = LinkedHashMap()

    /** Constructors, keyed by parameter count, so a construction can pick one. */
    val constructors: MutableMap<Int, FunctionSymbol> = LinkedHashMap()

    /**
     * The one superclass this type extends, or `null` when it extends nothing.
     *
     * A class extends at most one class; an interface extends none, and instead lists its parents in
     * [interfaces]. Both are kept because the JVM distinguishes them in the class file, and because
     * a superclass is the only one whose constructor a subclass must call.
     */
    var superclass: ClassType? = null

    /** Every interface this type implements or extends, in declaration order. */
    val interfaces: MutableList<ClassType> = mutableListOf()

    /**
     * The class this one extends, when it is declared in the same file.
     *
     * Needed for member lookup: an inherited field or function is reachable on the subclass, so
     * resolution has to walk up. `null` when the superclass lives outside this compilation unit.
     */
    var superclassSymbol: ClassSymbol? = null

    /**
     * Every interface this type implements, resolved to a symbol where the declaration is visible.
     *
     * An interface's abstract methods have to be found on an implementing class just as an inherited
     * superclass method does, so lookup walks these too.
     */
    val interfaceSymbols: MutableList<ClassSymbol> = mutableListOf()

    /** Finds the overloads of [name] that accept exactly [arity] arguments. */
    fun overloads(name: String, arity: Int): List<FunctionSymbol> =
        functions[name].orEmpty().filter { it.parameterTypes.size == arity }

    /**
     * Finds a field by [name], searching this class and then its superclasses.
     *
     * An inherited field is reachable on the subclass exactly as if it were declared there, so a
     * lookup that stopped at this class would reject a legal program.
     */
    fun findField(name: String): FieldSymbol? {
        fields[name]?.let { return it }
        return superclassSymbol?.findField(name)
            ?: interfaceSymbols.firstNotNullOfOrNull { it.findField(name) }
    }

    /**
     * Every field visible on this type, including inherited ones, keyed by name.
     *
     * A subclass field of the same name shadows the inherited one, matching the JVM: the entry for
     * the name is the field resolution would actually find.
     */
    fun allFields(): Map<String, FieldSymbol> {
        val inherited = LinkedHashMap<String, FieldSymbol>()
        superclassSymbol?.let { inherited.putAll(it.allFields()) }
        for (implemented in interfaceSymbols) {
            for ((name, field) in implemented.allFields()) inherited.putIfAbsent(name, field)
        }
        return if (inherited.isEmpty()) fields else inherited.apply { putAll(fields) }
    }

    /**
     * Every function visible on this type, including inherited ones.
     *
     * Overloads accumulate rather than replace: a subclass may add an overload of an inherited name,
     * and both remain callable. An interface's methods are reachable on a class that implements it,
     * which is what lets a call through an interface-typed value resolve at all.
     */
    fun allFunctions(): Map<String, List<FunctionSymbol>> {
        if (superclassSymbol == null && interfaceSymbols.isEmpty()) return functions

        val merged = LinkedHashMap<String, MutableList<FunctionSymbol>>()
        superclassSymbol?.let { parent ->
            for ((name, overloads) in parent.allFunctions()) {
                merged.getOrPut(name) { mutableListOf() } += overloads
            }
        }
        for (implemented in interfaceSymbols) {
            for ((name, overloads) in implemented.allFunctions()) {
                // An interface method implemented here must not appear twice, so a signature already
                // present from the class itself wins.
                val existing = merged.getOrPut(name) { mutableListOf() }
                for (overload in overloads) {
                    val replaced = existing.indexOfFirst { it.parameterTypes == overload.parameterTypes }
                    if (replaced >= 0) existing[replaced] = overload else existing += overload
                }
            }
        }
        for ((name, overloads) in functions) {
            val existing = merged.getOrPut(name) { mutableListOf() }
            for (overload in overloads) {
                val replaced = existing.indexOfFirst { it.parameterTypes == overload.parameterTypes }
                if (replaced >= 0) existing[replaced] = overload else existing += overload
            }
        }
        return merged
    }

    /** `true` when this type is, or inherits from, [other]. */
    fun isSubtypeOf(other: ClassSymbol): Boolean =
        this === other ||
            superclassSymbol?.isSubtypeOf(other) == true ||
            interfaceSymbols.any { it.isSubtypeOf(other) }

    /**
     * `true` when this type, or one of its supertypes, is named [jvmInternalName].
     *
     * Both the declared interfaces and their symbols are walked, and that pair is not redundant: an
     * interface the program declares has a symbol, so `interfaceSymbols` can follow its own
     * supertypes; one it merely names — `java.util.Iterator` in `class Cursor: Iterator` — has no
     * symbol at all, because the program does not compile the JDK. In that case the [interfaces]
     * type is the only record of what was written, and skipping it would make implementing a JDK
     * interface invisible to every subtype question.
     */
    fun descendsFrom(jvmInternalName: String): Boolean =
        this.jvmInternalName == jvmInternalName ||
            superclass?.internalName == jvmInternalName ||
            interfaces.any { it.internalName == jvmInternalName } ||
            superclassSymbol?.descendsFrom(jvmInternalName) == true ||
            interfaceSymbols.any { it.descendsFrom(jvmInternalName) }

    override fun toString(): String =
        "${if (isInterface) "interface" else "class"} $jvmInternalName"
}

/** A `val`/`var` declared directly in a class body. */
class FieldSymbol(
    override val name: String,
    override val type: NekoType,
    override val span: Span,
    val kind: VariableKind,
    val modifier: String?,
    val enclosingClass: ClassSymbol,
    /**
     * `true` when the field was declared `static`.
     *
     * A non-static field lives on an instance, so reaching it needs a receiver (`obj.f`) — or an
     * implicit `this` from inside a non-static member of the same class.
     */
    val isStatic: Boolean = false,
) : Symbol {
    /** `true` when the field may be assigned after initialization. */
    val isMutable: Boolean get() = kind == VariableKind.VAR

    /** JVM field descriptor, filled in by the code generator on demand. */
    var jvmDescriptor: String = ""

    override fun toString(): String = "field $name: $type"
}

/** A function declared in a class body, or one of the language's built-ins. */
class FunctionSymbol(
    override val name: String,
    override val span: Span,
    val parameterTypes: List<NekoType>,
    val returnType: NekoType,
    /** `null` for built-in functions such as `println`. */
    val enclosingClass: ClassSymbol?,
    /** `null` for built-in functions, and for a constructor (see [declaration]). */
    val declaration: FunctionDeclaration?,
    val builtin: BuiltinFunction? = null,
    /**
     * `true` when the function was declared `static`.
     *
     * A non-static function is invoked on an instance, so a call needs a receiver (`obj.f(...)`) — or
     * an implicit `this` from inside a non-static member of the same class. Built-ins are static
     * except for the `System.out` methods, which are modelled separately.
     */
    val isStatic: Boolean = true,
    /**
     * The types named by the declaration's `emit` clause, empty when it has none.
     *
     * A non-empty list means the function's `scratch` sites reach a handler with those values: a
     * caller must declare one with `try f(...) { a, b -> ... }`. The list is also the *signature* a
     * `scratch` is matched on, so it is what decides which handler a site belongs to.
     */
    val emitTypes: List<NekoType> = emptyList(),
    /**
     * What `emit [...] -> R` promises its handlers answer.
     *
     * A `try` handler for this function checks every `nyan` against this type, and a `scratch` inside
     * a function declaring the same clause is worth exactly it. A clause that wrote no result — and
     * one that wrote `-> void` — is [com.github.kusa233.neko.types.VoidType]: the handler answers
     * nothing, so a valued `nyan` is refused and a `scratch` site is a statement rather than a value.
     *
     * `null` only when no clause was declared at all, or after an unresolved one; [hasEmitClause]
     * tells the two apart.
     */
    val emitResultType: NekoType? = null,
    /** `true` when the declaration carried an `emit` clause, even an empty one such as `emit []`. */
    val hasEmitClause: Boolean = false,
    /**
     * The `ScratchHandlerN` interface this function takes as a hidden last parameter.
     *
     * `null` when the function declares no `emit` clause. Otherwise it is the standard library's
     * interface for the clause's arity — `ScratchHandler2` for `emit [int, int]` — and the code
     * generator appends it to the descriptor and passes a value for it at every call.
     *
     * This is what makes a `scratch` a *call through a value* rather than a jump to a statically
     * located method: the handler travels with the call, so several `try` blocks can coexist for the
     * same `emit` signature, and one callee can be reached from all of them.
     */
    val handlerType: NekoType? = null,
    /**
     * The constructor this symbol describes, or `null` when it is an ordinary function.
     *
     * A constructor is not a callable member — nothing names it — so it is modelled separately from
     * the functions a program can call, but it still needs a symbol for its body to be generated.
     */
    val constructor: ConstructorDeclaration? = null,
    /**
     * `true` when this function is declared in an interface and has no body.
     *
     * Such a method is emitted with `ACC_ABSTRACT` and no code; every implementing class must
     * provide it, which the JVM verifies at load time.
     */
    val isAbstract: Boolean = false,
    /**
     * The JVM class that actually declares this method, when it is not [enclosingClass].
     *
     * A member a class *inherits* is reachable through the class without being declared in its class
     * file, so `invokevirtual` has to name the class the JVM will really find the method in.
     * `java.lang.Enum`'s `name()` and `ordinal()` are the case this exists for: an enum's symbol table
     * lists them — member lookup reads that table — but the code belongs to `Enum`.
     *
     * `null` when the method is declared in [enclosingClass] itself, which is the ordinary case.
     */
    val inheritedFrom: String? = null,
) : Symbol {
    /** The function's own type, e.g. `[] -> [int, int] -> int`. */
    override val type: NekoType = FunctionType(parameterTypes, returnType)

    /** `true` when calling this function may reach a `try` handler through `scratch`. */
    val canRaise: Boolean get() = hasEmitClause

    /**
     * The parameters as they appear in the class file: the written ones plus the hidden handler.
     *
     * Kept apart from [parameterTypes] rather than added to it, because the two answer different
     * questions and would break different things if merged. [parameterTypes] is what a *call site*
     * writes and what overload resolution compares, so it must stay exactly as declared. This is what
     * the JVM sees, so it is what descriptors are built from and what a call must push values for.
     */
    val descriptorParameterTypes: List<NekoType>
        get() = if (handlerType == null) parameterTypes else parameterTypes + handlerType

    /** `true` when this symbol describes a constructor rather than a callable function. */
    val isConstructor: Boolean get() = constructor != null

    /** Parameter names, used by the code generator for local-variable debug info. */
    val parameterNames: List<String>
        get() = constructor?.parameters?.map { it.name } ?: declaration?.parameters?.map { it.name }.orEmpty()

    /** How the call appears in the class file. */
    val jvmOwner: String
        get() = builtin?.jvmOwner ?: inheritedFrom ?: enclosingClass!!.jvmInternalName

    /**
     * `true` when the method must be dispatched with `invokeinterface`.
     *
     * The JVM has a separate instruction for a call through an interface, and it is a verification
     * error to use `invokevirtual` on one. A method declared in an interface — an abstract
     * `invoke`, or a default `extract` — is therefore dispatched differently from a class method.
     */
    val ownerIsInterface: Boolean get() = enclosingClass?.isInterface == true

    override fun toString(): String =
        if (isConstructor) "constructor(${parameterTypes.joinToString(", ") { it.displayName }})"
        else "fun $name(${parameterTypes.joinToString(", ") { it.displayName }}): $returnType"
}

/** A function or method supplied by the language itself. */
class BuiltinFunction(
    val jvmOwner: String,
    val jvmName: String,
    val jvmDescriptor: String,
    /**
     * `true` for methods called on `System.out`, which the code generator loads with a
     * `GETSTATIC java/lang/System.out` before pushing the arguments.
     */
    val receiverIsSystemOut: Boolean,
)

/** A local variable, a function parameter, or a lambda parameter. */
class LocalSymbol(
    override val name: String,
    override val type: NekoType,
    override val span: Span,
    val kind: VariableKind,
    /** `true` for function/lambda parameters, which have no `val`/`var` keyword. */
    val isParameter: Boolean = false,
    /**
     * The identity of the innermost lambda that encloses this declaration, or `null` when the
     * declaration lives in an ordinary function body.
     *
     * Compared by identity: two locals are in the same lambda exactly when they hold the same
     * identity object. A reference from a *different* lambda is therefore a capture.
     */
    val enclosingLambda: Any? = null,
) : Symbol {
    /** Parameters are assignable unless declared `val`; locals follow their keyword. */
    val isMutable: Boolean get() = if (isParameter) true else kind == VariableKind.VAR

    override fun toString(): String = "${if (isParameter) "parameter" else "variable"} $name: $type"
}
