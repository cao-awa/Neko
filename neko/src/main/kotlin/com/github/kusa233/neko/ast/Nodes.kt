package com.github.kusa233.neko.ast

import com.github.kusa233.neko.source.Span
import com.github.kusa233.neko.lexer.TokenType
import com.github.kusa233.neko.symbols.ClassSymbol
import com.github.kusa233.neko.symbols.FieldSymbol
import com.github.kusa233.neko.symbols.FunctionSymbol
import com.github.kusa233.neko.symbols.LocalSymbol
import com.github.kusa233.neko.symbols.Symbol
import com.github.kusa233.neko.types.NekoType
import com.github.kusa233.neko.types.ClassType
import com.github.kusa233.neko.types.ErrorType

/**
 * The Neko abstract syntax tree.
 *
 * Every node carries the exact [span] it was parsed from, which is what allows the analyzer and the
 * code generator to point at the precise offending construct.
 *
 * Nodes also expose a few `var` properties (documented as *analysis results*) that the analyzer
 * fills in. Keeping them on the node means the code generator needs no parallel side tables.
 */
sealed interface Node {
    val span: Span
}

// --------------------------------------------------------------------------- file level

/**
 * A parsed `.neko` file: an optional `locate` clause, any `import` clauses, then class declarations.
 */
class NekoFile(
    val packageDeclaration: PackageDeclaration?,
    /**
     * The `import` clauses, in source order.
     *
     * Neko has no `import` in the Java sense of pulling one name into scope for one file: a class
     * is named by its `locate` package, and an import is what lets a file reach a class whose
     * package it does not share. The standard library is imported for every file without one being
     * written, which is why a program may name `EarlyEmitException` from any package.
     */
    val imports: List<ImportDeclaration>,
    val classes: List<ClassDeclaration>,
    override val span: Span,
) : Node {
    /**
     * The packages whose classes [name] may resolve to, most specific first.
     *
     * This file's own package comes first, so a local name always wins over an imported one; then
     * the imports in source order. The standard library's package is appended by the analyzer rather
     * than stored here, because it is implicit in every file.
     *
     * A single-class import contributes its own *package*, not the class: `import java.util.HashMap`
     * is a statement about where `HashMap` lives, and the simple name is what the file then writes.
     * The class it names is pinned by [NekoFile.importedClasses] so that the same clause cannot also
     * be read as a package called `java.util.HashMap`.
     */
    fun importPrefixes(): List<String> = imports.map { it.packageName.replace('.', '/') + "/" }

    /**
     * The simple names this file's single-class imports pin to one exact class, by simple name.
     *
     * `import java.util.HashMap` fixes `HashMap`; without this the clause would be
     * indistinguishable from `import java.util` and `HashMap` would resolve the same way it would
     * anyway. What pinning buys is the *restriction*: when a file says which `List` it means, a
     * second package holding a `List` cannot quietly answer instead.
     */
    fun importedClasses(): Map<String, ImportDeclaration> =
        imports.filter { it.isClassImport }.associateBy { it.simpleName!! }
}

/**
 * `import java.util` — a package — or `import java.util.HashMap` — a single class.
 *
 * One clause covers both, because the two spellings are the same shape and only the *target* tells
 * them apart: `java.util.HashMap` names a class, `java.util` names a package. The parser cannot know
 * which, so it records what was written and [isClassImport] records what the analyzer decided once it
 * could ask the platform. Nothing here is a per-class alias and nothing hides a declaration:
 * resolution still prefers the file's own package and the program's own classes, so an import can
 * only ever *add* a way to resolve a name.
 *
 * There is no `.*` form, and its absence is deliberate. A wildcard would be a second way to say what
 * a bare `import java.util` already means, and the language's rule is that an import names what it
 * wants. Writing `import java.util.*` is therefore reported with the exact form to write instead.
 */
class ImportDeclaration(
    /**
     * The name exactly as written, e.g. `java.util` or `java.util.HashMap`.
     *
     * Kept verbatim because diagnostics quote it back, and because the analyzer needs the original
     * dotted form to split into package and class before it can decide which one it is.
     */
    val name: String,
    /** Individual dotted segments, used to point at one segment in diagnostics. */
    val segments: List<String>,
    override val span: Span,
    /**
     * Whether the analyzer found [name] to be a class rather than a package.
     *
     * Filled in by analysis, not by the parser: whether `java.util.HashMap` names a class is a
     * question only the platform can answer. Defaults to `false` so a declaration built before
     * analysis reads as the package import it was when nothing said otherwise.
     */
    var isClassImport: Boolean = false,
) : Node {

    /** The package this clause pulls into view, whichever of the two kinds it turned out to be. */
    val packageName: String get() = if (isClassImport) name.substringBeforeLast('.') else name

    /** The class this clause pins, or `null` when it imports a whole package. */
    val simpleName: String? get() = if (isClassImport) name.substringAfterLast('.') else null
}

/**
 * Every `.neko` file being compiled together, as one program.
 *
 * Neko is compiled a directory at a time — the standard library is written in Neko, so a program
 * and the classes it names are separate files that must see each other's declarations. One shared
 * symbol table over all of them is what makes `EarlyEmitException` resolve to the class another file
 * declares rather than to a class the compiler has never seen.
 *
 * The files are kept in the order they were given, which is what makes diagnostic output and the
 * order of generated classes deterministic: a build must not depend on how a directory happened to
 * be enumerated.
 */
class NekoProgram(val files: List<NekoFile>) {
    /** Every class declaration in the program, in file order. */
    val classes: List<ClassDeclaration> = files.flatMap { it.classes }

    /** The package prefix, e.g. `com/example/`, that [declaration]'s file declares. */
    fun packagePrefixOf(declaration: ClassDeclaration): String =
        packagePrefixOf(fileOf(declaration))

    /** The file [declaration] was parsed from, or `null` when it is not part of this program. */
    fun fileOf(declaration: ClassDeclaration): NekoFile? = owningFiles[declaration]

    private val owningFiles: Map<ClassDeclaration, NekoFile> =
        files.flatMap { file -> file.classes.map { it to file } }.toMap()

    private fun packagePrefixOf(file: NekoFile?): String {
        val packageName = file?.packageDeclaration?.name
        return if (packageName.isNullOrEmpty()) "" else packageName.replace('.', '/') + "/"
    }

    companion object {
        /** The package prefix of [file]'s `locate` clause, in JVM internal form. */
        fun packagePrefixOf(file: NekoFile): String {
            val packageName = file.packageDeclaration?.name
            return if (packageName.isNullOrEmpty()) "" else packageName.replace('.', '/') + "/"
        }
    }
}

/** `locate com.github.kusa233.sample` */
class PackageDeclaration(
    /** Fully-qualified package name, e.g. `com.github.kusa233.sample`. */
    val name: String,
    /** Individual dotted segments, used to point at one segment in diagnostics. */
    val segments: List<String>,
    override val span: Span,
) : Node

/** Access modifier kinds accepted on a class or member. */
enum class ModifierKind(val display: String) {
    PUBLIC("public"),
    PRIVATE("private"),
    PROTECTED("protected"),
    STATIC("static"),
}

class Modifier(val kind: ModifierKind, override val span: Span) : Node

/** The JVM visibility a list of modifiers asks for; `public` is the default. */
internal fun List<Modifier>.visibility(): ModifierKind = when {
    any { it.kind == ModifierKind.PRIVATE } -> ModifierKind.PRIVATE
    any { it.kind == ModifierKind.PROTECTED } -> ModifierKind.PROTECTED
    else -> ModifierKind.PUBLIC
}

/** Whether a variable may be reassigned after its declaration. */
enum class VariableKind(val display: String) {
    VAL("val"),
    VAR("var"),
}

/**
 * A `class` or `interface` declaration.
 *
 * Both are represented by this node because they differ in only a few respects: an interface may not
 * declare instance fields and its functions may be abstract (no body) or default (a body), while a
 * class may extend exactly one class and implement any number of interfaces. Keeping one node means
 * name resolution, member lookup and code generation handle both without parallel paths.
 *
 * A `meow class` is the same node again, for the same reason: see [isMeow].
 */
class ClassDeclaration(
    val name: String,
    val nameSpan: Span,
    /**
     * Type parameters written as `class Box<T, U>`, erased to `Object` at run time.
     *
     * Neko has no generic instantiation syntax, so a type parameter is only ever a name for
     * "whatever the caller passes, boxed" — which is exactly `Object` once the JVM erases it. That
     * is enough for the standard library's `EmitExtractorN`, whose methods do nothing but forward.
     */
    val typeParameters: List<String> = emptyList(),
    val modifiers: List<Modifier>,
    /** The single superclass named after `:`, or `null` when none was written. */
    val superclass: SuperTypeReference? = null,
    /** Every interface named after `:`, in declaration order. */
    val interfaces: List<SuperTypeReference> = emptyList(),
    val fields: List<FieldDeclaration>,
    val constructors: List<ConstructorDeclaration> = emptyList(),
    val functions: List<FunctionDeclaration>,
    /** `true` when this was written `interface`, which changes what the class file may declare. */
    val isInterface: Boolean = false,
    /**
     * `true` when the declaration was written `meow class`, i.e. its fields were named in its header.
     *
     * The header is desugared in the parser — `meow class Point(val x: int)` becomes a field, a
     * constructor parameter and a `this.x = x` assignment — so nothing downstream needs a second path
     * for meow fields or constructors. What is left is the one thing Neko cannot express: this class'
     * `equals` and `hashCode` are derived from its fields and emitted as bytecode.
     */
    val isMeow: Boolean = false,
    /**
     * `true` when the declaration was written `miao class`, i.e. it is an enum.
     *
     * The entries in [entries] are desugared by the analyzer into `static final` fields of this
     * class' own type, each initialized in `<clinit>` from a private constructor the compiler emits.
     * Nothing downstream needs a second notion of "constant": an entry is an ordinary static field
     * that happens to have been created by the compiler rather than by a reader's initializer.
     */
    val isMiao: Boolean = false,
    /**
     * The constants of a `miao class`, in declaration order, and empty for every other declaration.
     *
     * The order is what `ordinal()` reports, so it is the source order and is never sorted.
     */
    val entries: List<EnumEntry> = emptyList(),

    override val span: Span,
) : Node {
    /**
     * Filled in by the analyzer: the symbol this declaration resolves to.
     *
     * The symbol table is keyed by fully qualified name, because two packages may each declare a
     * `Helper`. This field is how a declaration names its own symbol without recomputing the package
     * prefix — the same reason [FunctionDeclaration.symbol] exists.
     */
    var symbol: ClassSymbol? = null
}

/**
 * One constant of a `miao class`, e.g. `RED` or `RED(1)` in `miao class Color { RED, GREEN }`.
 *
 * The constructor arguments are expressions rather than values because they are analyzed in the
 * enum's own scope: `miao class Code { OK(STATUS_OK) }` reads a `static` field of a class the
 * program declares, and that name has to resolve like any other.
 *
 * The entry's own name is what `name()` reports and what `valueOf` matches, and the compiler uses
 * that same string as the constant's field name, so the two can never disagree.
 */
class EnumEntry(
    val name: String,
    val nameSpan: Span,
    /** The arguments written after the name, empty when it was written bare. */
    val arguments: List<Expression> = emptyList(),
    override val span: Span,
) : Node {
    /** Filled in by the analyzer: the entry as a static field of the enclosing enum. */
    var symbol: FieldSymbol? = null
}

/**
 * A supertype named in a class header, optionally with constructor arguments.
 *
 * `class EarlyEmitException: RuntimeException()` names `RuntimeException` and asks for its no-argument
 * constructor; `interface EmitExtractor2: EmitExtractor` names an interface and passes no arguments.
 * The arguments are expressions rather than type references because that is what a constructor call
 * takes, and they are analyzed in the subclass' own constructor scope.
 */
class SuperTypeReference(
    val name: String,
    val nameSpan: Span,
    /** `null` when no parenthesis followed the name, i.e. this names an interface. */
    val arguments: List<Expression>?,
    /**
     * The type arguments written on the supertype: the `<int>` in `class Numbers: Iterable<int>`.
     *
     * Erased from the class file like every other generic, but read by `for ... in` to know what an
     * iterable yields. Empty when none were written — including for a superclass, whose arguments a
     * Neko declaration does not spell out.
     */
    val typeArguments: List<TypeReference> = emptyList(),
    override val span: Span,
) : Node {
    /** Filled in by the analyzer: the resolved supertype. */
    var resolvedType: NekoType? = null
}

/** `constructor(data: Object[]) { ... }` */
class ConstructorDeclaration(
    val parameters: List<Parameter>,
    val body: BlockStatement,
    val modifiers: List<Modifier>,
    override val span: Span,
) : Node {
    /** Filled in by the analyzer. */
    var symbol: FunctionSymbol? = null

    /** Filled in by the analyzer, so code generation and analysis agree on parameter slots. */
    var parameterSymbols: List<LocalSymbol> = emptyList()

    val isPrivate: Boolean get() = modifiers.visibility() == ModifierKind.PRIVATE
    val isProtected: Boolean get() = modifiers.visibility() == ModifierKind.PROTECTED
}

/** `private val field1: int = 123` */
class FieldDeclaration(
    val kind: VariableKind,
    /** Span of the `val`/`var` keyword, or `null` when the keyword was omitted. */
    val kindSpan: Span?,
    val name: String,
    val nameSpan: Span,
    val declaredType: TypeReference,
    val initializer: Expression?,
    val modifiers: List<Modifier>,
    override val span: Span,
) : Node {
    /** Filled in by the analyzer. */
    var resolvedType: NekoType? = null

    /** Filled in by the analyzer. */
    var symbol: FieldSymbol? = null

    /** `true` when this field was declared `static`, i.e. is reachable without an instance. */
    val isStatic: Boolean get() = modifiers.any { it.kind == ModifierKind.STATIC }
}

/**
 * `fun main(args: string[]) { ... }`.
 *
 * A function may also be *abstract*, written with no body at all, which is only legal inside an
 * interface; see [isAbstract].
 */
class FunctionDeclaration(
    val name: String,
    val nameSpan: Span,
    val parameters: List<Parameter>,
    /** `null` when the function declares no return type, i.e. it returns nothing. */
    val returnType: TypeReference?,
    /** `null` for an abstract function, which declares a signature and no body. */
    val body: BlockStatement?,
    val modifiers: List<Modifier>,
    /**
     * The result types named by a trailing `emit [int, int]` clause, always empty when the clause is
     * absent.
     *
     * A function with an `emit` clause gains a second way out: a `scratch a, b` statement inside it
     * passes those values to the handler the caller's `try` declared, and carries on with whatever
     * that handler answers. The clause is also what a `scratch` site is *matched* on, so the types
     * say what the handler receives. The paths that never scratch still return a value of the
     * declared return type, so the clause says "may reach a handler", not "never returns". The clause
     * is written after the body.
     */
    val emitTypes: List<TypeReference> = emptyList(),
    /**
     * `true` when an `emit` clause was written at all.
     *
     * Distinct from `emitTypes.isNotEmpty()`, because `emit []` is a legal clause naming zero
     * values: a `scratch` with nothing to carry, matched by the caller's `try f() { -> ... }`.
     */
    val hasEmitClause: Boolean = false,
    override val span: Span,
) : Node {
    /** Filled in by the analyzer: resolved parameter types, in declaration order. */
    var resolvedParameterTypes: List<NekoType>? = null

    /** Filled in by the analyzer: `VoidType` when no return type was declared. */
    var resolvedReturnType: NekoType? = null

    /** Filled in by the analyzer: the types named by the `emit` clause, in declaration order. */
    var resolvedEmitTypes: List<NekoType> = emptyList()

    /** Filled in by the analyzer: the symbol code generation needs to emit a call to this function. */
    var symbol: FunctionSymbol? = null

    /** `true` when this member was declared `static`, i.e. is reachable without an instance. */
    val isStatic: Boolean get() = modifiers.any { it.kind == ModifierKind.STATIC }

    val isPrivate: Boolean get() = modifiers.visibility() == ModifierKind.PRIVATE

    /** `true` when the declaration has no body: a signature an implementer must provide. */
    val isAbstract: Boolean get() = body == null
}

/**
 * A declared parameter of a function.
 *
 * Lambda parameters use [LambdaParameter] instead, because their type may be inferred.
 */
class Parameter(
    val name: String,
    val nameSpan: Span,
    val declaredType: TypeReference,
    override val span: Span,
) : Node

// ------------------------------------------------------------------------- type syntax

/** A type as written in the source; resolved to an [NekoType] by the analyzer. */
sealed interface TypeReference : Node

/** `int`, `string`, `bool` */
/**
 * A type written by name: `int`, `Color`, `Iterable<string>`.
 *
 * [arguments] carries whatever was written between `<` and `>` — the `string` in `Iterable<string>`.
 * Neko erases generics, so these never reach the class file; they are kept because `for ... in` has to
 * know what an iterable yields in order to check the loop variable against it and to unbox `next()`.
 * Everywhere else the list is simply empty.
 */
class NamedTypeReference(
    val name: String,
    val nameSpan: Span,
    val arguments: List<TypeReference> = emptyList(),
    override val span: Span,
) : TypeReference

/** `string[]` */
class ArrayTypeReference(val element: TypeReference, override val span: Span) : TypeReference

/** `[int, int] -> int` */
class FunctionTypeReference(
    val parameters: List<TypeReference>,
    val returnType: TypeReference,
    override val span: Span,
) : TypeReference

/**
 * `string?`, `int?`, `Point?` — a type that additionally admits `null`.
 *
 * The question mark is kept as its own node rather than being folded into the inner reference so
 * that the inner type is resolved by exactly the code that resolves it without one, and so a
 * diagnostic about `int?` can point at the `?` that made it nullable.
 */
class NullableTypeReference(
    val inner: TypeReference,
    val questionSpan: Span,
    override val span: Span,
) : TypeReference

// -------------------------------------------------------------------------- statements

sealed interface Statement : Node

/** `{ ... }` */
class BlockStatement(val statements: List<Statement>, override val span: Span) : Statement

/**
 * A local variable declaration: `str: string = "Hell world!"`, optionally prefixed with `val`/`var`.
 */
class VariableDeclarationStatement(
    val kind: VariableKind,
    val kindSpan: Span?,
    val name: String,
    val nameSpan: Span,
    val declaredType: TypeReference,
    /** `null` when no initializer was written; the analyzer reports that as an error. */
    val initializer: Expression?,
    override val span: Span,
) : Statement {
    /** Filled in by the analyzer. */
    var symbol: LocalSymbol? = null

    /** Filled in by the analyzer. */
    var resolvedType: NekoType? = null
}

/**
 * `str = "new value"`, or `obj.field = value` when the target is an instance field.
 *
 * The target is an expression rather than a plain name so a field can be assigned through its
 * receiver; the analyzer accepts only the two shapes that can be written to — a name, or a member
 * access — and reports anything else at the target's own span.
 */
class AssignmentStatement(
    val target: Expression,
    val value: Expression,
    override val span: Span,
) : Statement {

    /**
     * Filled in by the analyzer: the local this statement *declares*, when no name was in scope.
     *
     * `instance = Instance()` and `str = "new value"` are spelled identically, and only the scope
     * tells them apart: a name that is already declared is assigned to, and a name that is not is
     * declared with its type inferred from the value.
     *
     * The parser cannot make that call — a bare name may also be a field declared further down the
     * same class, which is why the decision is not simply "declared earlier in the body". So the
     * statement is always parsed as an assignment, and the analyzer records here that it turned out
     * to be a declaration. That record is what tells the code generator to give the name a fresh
     * slot, rather than store into the slot of a local that already has one.
     */
    var declaredLocal: LocalSymbol? = null
}

/** A statement whose value is discarded, such as `println(field1)`. */
class ExpressionStatement(val expression: Expression, override val span: Span) : Statement

/** `return`, optionally with a value. */
class ReturnStatement(
    val keywordSpan: Span,
    val value: Expression?,
    override val span: Span,
) : Statement

/**
 * `yield x + y` — produces the value of the construct this `yield` answers.
 *
 * [value] is what the `yield` carries, and it is **optional**. A bare `yield` carries nothing, which
 * is how a handler abandons a call whose `try` wanted no value:
 *
 * ```neko
 * try call(...) { a -> yield }    // leave through the try, answering nothing
 * ```
 *
 * Writing a value where nothing reads it is a mistake rather than a convenience, and so is omitting
 * one where something is waiting, so the analyzer requires the two to agree: a bare `yield` only in a
 * `try` that is worth nothing, and a valued one only in a `try` that is worth something.
 */
class YieldStatement(
    val keywordSpan: Span,
    val value: Expression?,
    override val span: Span,
) : Statement

/** `if (condition) thenBranch else elseBranch` */
class IfStatement(
    val condition: Expression,
    val thenBranch: Statement,
    val elseBranch: Statement?,
    override val span: Span,
) : Statement

/** `while (condition) body` */
class WhileStatement(
    val condition: Expression,
    val body: Statement,
    override val span: Span,
) : Statement

/**
 * `for (i: int = 0; i < 100; i++) body` — the three-part loop, spelled as Java spells it.
 *
 * The four pieces are Java's: [initializer] runs once, [condition] is checked before every pass,
 * [update] runs after every pass, and [body] runs in between. All three of the first are optional,
 * which is what makes `for (;;)` an infinite loop rather than a syntax error.
 *
 * [initializer] declares the loop variable, and that declaration is scoped to the loop: its type is
 * the variable's type, and it is not visible after the loop ends. That is why it is a whole
 * [VariableDeclarationStatement] rather than a name — a `for` that could only reuse an existing
 * variable would make the two forms of `i` in `for (i: int = 0; ...)` and `for (i = 0; ...)` differ
 * only by a colon, and the scoping question that raises is not worth asking a reader to answer.
 */
class ForStatement(
    /** Span of the `for` keyword, for pointing at the loop rather than at its parts. */
    val keywordSpan: Span,
    /** `i: int = 0`, or `null` when the loop is written `for (; ...)`. */
    val initializer: VariableDeclarationStatement?,
    /** Checked before each pass; `null` means "always", i.e. `for (;;)`. */
    val condition: Expression?,
    /**
     * Run after each pass. Restricted to the two increments and an assignment, because a `for`'s
     * update is a statement and those are the only statements that fit between the semicolons.
     */
    val update: Statement?,
    val body: Statement,
    override val span: Span,
) : Statement

/**
 * `for (element: int in list) body` — run the body once per element, front to back.
 *
 * Two things may be iterated: an array, whose elements are read inline, and any type that implements
 * the standard library's `Iterable<T>`, which is walked with `iterator()`/`hasNext()`/`next()` the way
 * Java's own for-each is. An array needs no `iterator()` because the JVM has no interface it could
 * implement, so the two cases are genuinely different lowerings rather than one behind the other.
 *
 * [element] is **read-only**. It is bound afresh on every pass and the next pass overwrites it, so an
 * assignment to it inside the body would be a value the program computes and immediately throws away —
 * almost always a mistake, and one no compiler-free reader would spot.
 */
class ForEachStatement(
    val keywordSpan: Span,
    /** The loop variable's name, as written. */
    val name: String,
    val nameSpan: Span,
    /** The declared element type, resolved by the analyzer. */
    val elementType: TypeReference,
    val iterable: Expression,
    val body: Statement,
    override val span: Span,
) : Statement {
    /** Filled in by the analyzer: the type [iterable] was found to have. */
    var iterableType: NekoType? = null

    /** Filled in by the analyzer: the type each element is bound as, after a widening or unboxing. */
    var resolvedElementType: NekoType? = null

    /** Filled in by the analyzer: the read-only local the loop variable names. */
    var symbol: LocalSymbol? = null

    /** Filled in by the code generator: the local slot the loop variable lives in. */
    var elementSlot: Int = -1
}

/**
 * `i++` / `i--` — add one to, or subtract one from, a variable in place.
 *
 * A statement rather than an expression: there is no value to read back. Java's `i++` has one, and it
 * has to be told which — the old value or the new — and nothing here would use either. Keeping it a
 * statement also means `i++` cannot be smuggled into a condition where a reader expects no side
 * effect.
 *
 * Only a variable may be incremented, and only a numeric one: `a[i]++` and `f()++` are refused, since
 * neither is a variable and neither has a slot to write back to.
 */
class IncrementStatement(
    /** The word itself, `++` or `--`, kept so a diagnostic can quote what was written. */
    val operator: String,
    val target: NameExpression,
    /** Span of the operator, so "wrong type here" points at `++` rather than at the name. */
    val operatorSpan: Span,
    override val span: Span,
) : Statement {
    /**
     * The field access this statement increments, when it was written `holder.count++`.
     *
     * [target] is then the field's bare name, which is not a local in scope — so the receiver is kept
     * here and the code generator reads and writes the field through it. `null` for `count++` on a
     * field of `this`, where the access needs no receiver, and for a plain local.
     */
    var memberTarget: MemberAccessExpression? = null
}

/**
 * `when (subject) { 3 -> ...; 2 -> { ... } }`
 *
 * Every branch states a value to compare against the subject, so the statement is exhaustive only
 * when one branch uses `else`.
 */
class WhenStatement(
    val subject: Expression,
    /** Span of the `when` keyword, so a bad subject can be pointed at precisely. */
    val keywordSpan: Span,
    val branches: List<WhenBranch>,
    /** `true` when the final branch was written as `else -> ...`. */
    val hasElseBranch: Boolean,
    override val span: Span,
) : Statement

/** One `value -> statement` arm of a [WhenStatement]. */
class WhenBranch(
    /** `null` for an `else` arm, which matches anything. */
    val condition: Expression?,
    /** Span of the `->`, used when reporting a branch that is missing its arrow. */
    val arrowSpan: Span,
    val body: Statement,
    override val span: Span,
) : Node

/**
 * `nya condition`, `nya condition { ... }`, `nya condition { ... } else { ... }` — states what the
 * program believes, and says what to do when it is wrong.
 *
 * This is where a program writes down a fact it relies on rather than a fact it computes. What going
 * wrong means is chosen by the writer:
 *
 * - Bare, `nya a == b` throws a `java.lang.AssertionError` whose message is the condition's own
 *   source text. The program stops where the belief was broken rather than somewhere later where the
 *   damage surfaces. Because the throw is the only outcome there is, the condition has to be a `bool`
 *   — anything else would be a check that could never fail.
 * - With a block, `nya a == b { ... }`, the block runs instead and control carries on after it. The
 *   block answers a condition that came back false **and** an `AssertionError` raised while the
 *   condition was being evaluated — by a bare `nya` of its own, or by one inside a function it
 *   called. That second way in is why the condition may be of any type at all: `nya method() { ... }`
 *   is a call whose failure is handled, and `void` has no false for the first way to test. This is
 *   what replaced `try`/`catch`.
 * - With a block and an `else`, `nya a == b { ... } else { ... }`, the construct is also worth a
 *   value: the `else` block yields the value for the case where the condition *ran to a true answer*,
 *   and the failure block yields it for the case where it was false or threw. That is the whole of
 *   the expression form — `count: int = nya cached != null { yield load() } else { yield cached }` —
 *   and it is why the two blocks are the two answers to one question rather than an error path and a
 *   normal path.
 *
 * A bare `nya` in expression position is refused: with nothing to say what the failure is worth,
 * there would be no value to give. That is a question about the *use*, not about this node — the very
 * same construct is a statement when it is written on its own — which is why the parser demands the
 * `else` only where a value is expected and the analyzer is what reports the rest.
 */
class NyaExpression(
    /** Span of the `nya` keyword, used when reporting a missing or mistyped condition. */
    val keywordSpan: Span,
    val condition: Expression,
    /**
     * The block run when the condition fails, or `null` for the bare form that throws instead.
     *
     * This is the `else`-less block: `nya a == b { recover() }`. When [elseBlock] is present this
     * block is the failure arm of the value, and when it is absent the block is the whole of the
     * recovery — control continues after it either way, because the failure has been handled. It is
     * reached both by a false condition and by an `AssertionError` thrown out of [condition].
     */
    val failureBlock: BlockStatement?,
    /**
     * The block that produces the value when the condition held, or `null`.
     *
     * Present only in the value form. Its `yield` fixes the type the expression is worth, and the
     * failure block's `yield` must agree with it. Unlike [failureBlock] it is reached only on the
     * reading where the condition ran to an answer, because a throw never gets this far.
     */
    val elseBlock: BlockStatement?,
    override val span: Span,
) : Expression() {
    /**
     * The type the `yield`s in [failureBlock] and [elseBlock] produce, fixed by the first one seen.
     *
     * Stays `null` for the forms that are worth nothing, and for a value form whose blocks never
     * `yield` — the latter is reported by the analyzer rather than left to produce a null value.
     * Code generation reads it to know what to unbox the result into.
     */
    var yieldedType: NekoType? = null
}

/**
 * A locking block — `purr`, `purred`, `purrcls` or `purredcls`. Run a block with a lock held.
 *
 * The lock is a `java.util.concurrent.locks.StampedLock`, which is never reentrant: taking the same
 * lock twice on one thread deadlocks. That is why [lock] is part of the node's identity for the
 * analyzer to check nesting with, and why the release cannot be left to the block's own code — it
 * has to happen on every way out, including a `return`, a `nyan`, and a `yield`.
 *
 * Two things are chosen, and each of the four words chooses both at once:
 *
 *  - **Which lock.** [lock] is [PurrLock.INSTANCE] for `purr`/`purred`, which is one lock per object —
 *    `synchronized (this)` — and [PurrLock.CLASS] for `purrcls`/`purredcls`, which is one lock per
 *    class, `synchronized (Cls.class)`. An instance lock needs a `this`, so a static function can only
 *    use the class one; the analyzer says so.
 *  - **Read or write.** [mode] is [PurrMode.WRITE] for `purr`/`purrcls` and [PurrMode.READ] for
 *    `purred`/`purredcls`, in which case several threads may be inside at once. A read lock is
 *    exclusive against writers only, which is the whole reason to reach for `StampedLock` instead of
 *    `ReentrantLock` — the latter has no shared mode, so a read-mostly field guarded by it loses the
 *    concurrency it was written for.
 *  - **Nesting.** A block inside another on the *same* lock would deadlock, since the outer one
 *    already holds it; on a *different* lock it is fine. The analyzer reports the first and allows
 *    the second.
 *
 * The block is a `BlockStatement` rather than a list because it is a scope: a local declared inside
 * it is not visible after it, exactly as with `if`. Nothing is worth a value — a critical section that
 * `return`s has already left the building, and the same reasoning applies to `yield`.
 */
class PurrStatement(
    /** Span of the locking keyword. */
    val keywordSpan: Span,
    /** The word that was written, e.g. `purredcls`. Used to name the form in a diagnostic. */
    val keyword: String,
    val lock: PurrLock,
    val mode: PurrMode,
    val body: BlockStatement,
    override val span: Span,
) : Statement

/** Which `StampedLock` a `purr` block holds — see [PurrStatement.lock]. */
enum class PurrLock {
    /** One lock per object: `purr { ... }` and `purred { ... }`. */
    INSTANCE,

    /** One lock per class: `purrcls { ... }` and `purredcls { ... }`. */
    CLASS,
}

/** How a `purr` block holds its lock — see [PurrStatement.mode]. */
enum class PurrMode {
    /** Exclusive, against readers and other writers: `purr` and `purrcls`. */
    WRITE,

    /** Shared, against other readers but not writers: `purred` and `purredcls`. */
    READ,
}

/**
 * `scratch e1, e2` — enters the `try` handler whose `emit [...]` signature these values match,
 * handing it the values and continuing with whatever it answers.
 *
 * This is an *expression*, not a statement: the handler is generated as an ordinary method, so a
 * `scratch` is a call. `v: int = scratch 1, 2` keeps the value it answers, and a bare `scratch 1, 2`
 * discards one — which is what decides whether the generated handler is declared to return a value or
 * `void`.
 *
 * Only valid inside a function that declares an `emit [...]` clause, and the number and types of the
 * values must match that clause.
 */
class ScratchExpression(
    val keywordSpan: Span,
    val values: List<Expression>,
    override val span: Span,
) : Expression()

/**
 * `nyan value` — leaves the enclosing `try` handler, answering [value].
 *
 * A handler is generated as a method whose return type the reaching `scratch` sites fix, so a `nyan`
 * is a `return` from that method. [value] is **optional**: a bare `nyan` answers nothing, which is
 * exactly right for a handler whose `scratch` sites all discard the answer — the method is `void` and
 * a bare `nyan` is its plain `return`.
 *
 * A handler that writes no `nyan` at all had better `yield` on every path: `yield` throws, so it never
 * falls off the end.
 */
class NyanStatement(
    val keywordSpan: Span,
    val value: Expression?,
    override val span: Span,
) : Statement

/**
 * The part of a `try` that declares a handler, shared by the statement and the expression form.
 *
 * The `{ ... }` block is compiled into a `static` method, and every `scratch` whose `emit` clause
 * matches this `try`'s callee calls it. The two forms differ only in what the construct is worth:
 * `try f(...) { a -> ... }` on its own is a statement and produces nothing, while
 * `r: int = try f(...) { a -> yield 1 }` is an expression worth what a `yield` carried — which is why
 * [acceptsYield] distinguishes the two and why only the expression form may `yield` for its value.
 */
interface ExtractorTry : Node {
    /** Span of the `try` keyword. */
    val keywordSpan: Span

    /** The call this `try` wraps, whose callee's `emit` clause names the handler's signature. */
    val call: CallExpression

    /** The names given to the handler's values, in declaration order. */
    val extractorParameters: List<LambdaParameter>

    val extractorBody: BlockStatement

    /** Span of the `->` that introduces the extractor body, used when it is missing. */
    val arrowSpan: Span?

    /** `true` for the expression form, whose handler produces the value the `try` is worth. */
    val acceptsYield: Boolean

    /**
     * The type the extractor's `yield` produces, fixed by the first one it writes.
     *
     * `null` means the body never `yield`s, so its handler never leaves that way.
     */
    var yieldedType: NekoType?

    /**
     * Everything the two later passes learn about the method this `{ ... }` block becomes.
     *
     * An interface cannot hold initialized state in Kotlin, so the mutable half of a handler lives in
     * this one object; both `try` forms own exactly one.
     */
    val handler: HandlerInfo
}

/**
 * What the analyzer and code generator decide about one `try` handler, which is emitted as a method.
 *
 * The block is not run by the `try` itself: it is a handler, reached by `scratch` sites, so the code
 * generator emits it as a static method of the enclosing class and each `scratch` calls it. That
 * makes three facts about it something two passes have to agree on — its signature, what it answers,
 * and what it is called — and they are all gathered here.
 */
class HandlerInfo {
    /**
     * The emit signature this handler's body is written against, recorded by the analyzer.
     *
     * Taken from the wrapped call's `emit [...]` clause. Nothing is matched against it any more — a
     * handler is a value a call carries — but it is what the interface's `Object`s are converted to
     * on entry, so the body can keep using the declared types.
     */
    var signature: List<NekoType> = emptyList()
}

/**
 * One `scratch` reaching a handler, as recorded by the analyzer.
 *
 * [signature] is the `emit` clause of the function the site was written in, which is what picks the
 * handler. [wantedType] is the type the site needs that handler to answer: the declared type of the
 * variable the `scratch` initialises, or `null` when the value is discarded and the handler must
 * answer nothing.
 */
class ScratchSite(
    val expression: ScratchExpression,
    val signature: List<NekoType>,
    val wantedType: NekoType?,
)

/**
 * `try call(args) { a, b -> ... }` — declares a handler for the call's `emit` clause, and runs
 * [extractorBody] whenever a `scratch` reaches it.
 *
 * This is the only construct that declares a handler. [extractorParameters] receive the `scratch`
 * site's values in declaration order; their types come from the callee's `emit` clause, so a
 * lambda-style parameter list needs no annotations. A call that never scratches never runs the body.
 *
 * A call to a function with an `emit` clause is *only* legal here — or in [TryExpression], which asks
 * the same call for a value: anywhere else it is an error, because such a call would have no handler
 * to reach.
 */
class TryStatement(
    override val keywordSpan: Span,
    /** The call this statement wraps. */
    override val call: CallExpression,
    override val extractorParameters: List<LambdaParameter>,
    override val extractorBody: BlockStatement,
    /** Span of the `->` that introduces the extractor body, used when it is missing. */
    override val arrowSpan: Span?,
    override val span: Span,
) : Statement, ExtractorTry {
    /** A statement is worth nothing, so its handler has no value to `yield`. */
    override val acceptsYield: Boolean get() = false

    /** A statement's handler can never `yield`, so this stays `null`. */
    override var yieldedType: NekoType? = null

    override val handler = HandlerInfo()
}

// ------------------------------------------------------------------------- expressions

/**
 * Base class of all expressions.
 *
 * [resolvedType] is the type computed by the analyzer and read back by the code generator; it is
 * `null` only before analysis or after an error, in which case [type] falls back to [ErrorType].
 */
sealed class Expression : Node {
    var resolvedType: NekoType? = null

    /** Type of this expression, or [ErrorType] when analysis did not produce one. */
    val type: NekoType get() = resolvedType ?: ErrorType
}

/**
 * `r: int = try f(...) { a -> yield 1 }` — the same handler-declaring construct as [TryStatement], but
 * worth a value.
 *
 * When the call returns normally the expression is worth whatever it returned. When a `yield` runs
 * instead, the expression is worth the value that `yield` threw, which is why the two paths must agree
 * on one type. A handler that only `nyan`s does *not* contribute a value here — its answer goes to the
 * `scratch` site that entered it, which is somewhere else entirely — so `yield` is the only way for
 * the handler to decide what the `try` itself is worth.
 */
class TryExpression(
    override val keywordSpan: Span,
    override val call: CallExpression,
    override val extractorParameters: List<LambdaParameter>,
    override val extractorBody: BlockStatement,
    override val arrowSpan: Span?,
    override val span: Span,
) : Expression(), ExtractorTry {
    /** An expression is worth a value, so its handler may `yield` one. */
    override val acceptsYield: Boolean get() = true

    override var yieldedType: NekoType? = null

    override val handler = HandlerInfo()
}

/**
 * `try call() scratch v1, v2` — runs [call], and if it throws a Java exception, sends it to the
 * handler that [scratch] reaches.
 *
 * This is the other half of `try`'s design. `try call() { a, b -> ... }` *declares* a handler and
 * catches the `yield` a handler might throw; this form catches a **Java exception** thrown by the
 * call, and answers it by entering a handler that already exists elsewhere — the one whose `emit`
 * clause matches the `scratch`'s values. So the two spellings answer different questions:
 *
 *  * the brace form says "what should happen when this call scratches";
 *  * this form says "what should happen when this call *fails*".
 *
 * [scratch] is not evaluated on the normal path. It names which handler to enter, and its values are
 * evaluated only when an exception is actually caught — so `try f() scratch log(), 1` does not call
 * `log()` unless `f` threw.
 *
 * The `nyan` inside the handler reached this way answers back to the `try`, because the `try` is what
 * called `scratch`: the value a handler `nyan`s comes back to the site that entered it, and that site
 * is here. That is what makes `v: int = try f() scratch 1, 2` work — `v` is `f()`'s return value
 * when it returns, and the handler's `nyan` answer when it threw.
 */
class TryScratchExpression(
    override val keywordSpan: Span,
    override val call: CallExpression,
    override val scratch: ScratchExpression,
    override val span: Span,
) : Expression(), TryScratch

/** `try call() scratch v1, v2` in statement position: the answer is discarded. */
class TryScratchStatement(
    override val keywordSpan: Span,
    override val call: CallExpression,
    override val scratch: ScratchExpression,
    override val span: Span,
) : Statement, TryScratch

/**
 * The part both `try call() scratch v1, v2` forms share.
 *
 * They differ only in what the construct is worth — the expression form is worth the call's return
 * value, or the handler's `nyan` answer when the call threw, while the statement form discards both —
 * so everything the two passes have to agree on lives here.
 */
interface TryScratch : Node {
    val keywordSpan: Span

    /** The call whose thrown Java exception this construct catches. */
    val call: CallExpression

    /**
     * The `scratch` naming the handler to enter on the exception path.
     *
     * Analyzed as an ordinary `scratch`, which is what requires the enclosing function to declare an
     * `emit` clause and links it to a handler. Its values are emitted only on the failure path.
     */
    val scratch: ScratchExpression
}

/** A bare identifier, such as `field1`, `str` or `println`. */
class NameExpression(
    val name: String,
    val nameSpan: Span,
    override val span: Span,
) : Expression() {
    /** Filled in by the analyzer. */
    var resolution: Symbol? = null
}

/**
 * A whole-number literal, with or without a trailing `L`/`l`.
 *
 * The value is stored as a `Long` because that is the widest whole number the language can spell, and
 * the *type* is not fixed here: `1` is an `int` where nothing says otherwise and a `byte`, `short`,
 * `char` or `long` where the surrounding context asks for one and the value fits. That is what lets
 * `x: byte = 1` mean what it looks like without making `int + long` legal — see the analyzer's
 * literal handling, which fills in the resolved type every expression carries.
 */
class IntegerLiteral(
    val value: Long,
    /** `true` when the source wrote an `L`/`l` suffix, which names the 64-bit type outright. */
    val isLong: Boolean,
    override val span: Span,
) : Expression() {
    /**
     * Filled in by the analyzer: the literal's value does not fit the type its context asked for.
     *
     * The fact is recorded rather than reported where it is discovered because the *value* is a
     * property of the literal while the context is a property of whoever wrote the expectation, and
     * only one of the two dozen callers that hand a type down is in a position to say both. Reporting
     * it there, once, is also what keeps one mistake from producing one message per mention.
     */
    var isOutOfRange: Boolean = false

    /** The type the context asked for and the value did not fit; meaningful only with [isOutOfRange]. */
    var outOfRangeType: NekoType? = null

    /**
     * Filled in by the analyzer: this literal is written under a `-`.
     *
     * `-128` is one value rather than a negation of `128`, and the difference is visible: 128 does not
     * fit in a `byte` and -128 does. The analyzer records the sign here so that both the range check
     * and the emitter see the number the reader wrote rather than the one the parser happened to read
     * first.
     */
    var isNegated: Boolean = false

    /** The value this literal names, sign included. */
    val signedValue: Long get() = if (isNegated) -value else value

    /** How the literal is spelled back to the reader in a diagnostic, sign included. */
    val signedText: String get() = if (isNegated) "-$value" else "$value"

    /**
     * The literal read as an `int`, which is what it is when no context gives it a type.
     *
     * The clamping is what keeps a rejected literal from also breaking code generation: the value the
     * reader is told about is out of range anyway, and a class file still has to come out so the rest
     * of the program's diagnostics are not lost with it.
     */
    val intValue: Int get() = signedValue.toInt()
}

/**
 * A fractional literal, e.g. `1.5` or `1.5f`.
 *
 * [isFloat] records whether the source asked for `float`, which only the *parser* can know: the
 * analyzer sees an `Expression` and would otherwise have to re-read the token's text, and the
 * emitter would have to guess. A `double` is what an unsuffixed decimal is, because that is the
 * wider type and Java's own default.
 */
class DoubleLiteral(val value: Double, val isFloat: Boolean, override val span: Span) : Expression() {
    /**
     * Filled in by the analyzer: it has already objected to the type this literal was written with.
     *
     * A field's initializer is analysed twice — once while signatures are resolved and once with its
     * class' body — and an objection about a *literal* is a fact about that literal rather than about
     * the visit, so it is remembered and made once.
     */
    var isTypeReported: Boolean = false
}

/**
 * A single-character literal, e.g. `'a'`.
 *
 * Kept distinct from [IntegerLiteral] rather than folded into it so that the diagnostic for
 * `x: bool = 'a'` can name a character, and so a reader of the tree can see which spelling was
 * written.
 */
class CharLiteral(val value: Char, override val span: Span) : Expression()

class StringLiteral(val value: String, override val span: Span) : Expression()

class BoolLiteral(val value: Boolean, override val span: Span) : Expression()

/**
 * `null` — the one value a nullable type exists to hold.
 *
 * It has no type of its own: it takes whichever nullable type its context asks for, exactly as an
 * integer literal takes the integral type it is used as. That is why the analyzer, not the parser,
 * decides what type this node has.
 */
class NullLiteral(override val span: Span) : Expression()

/** `left operator right` */
class BinaryExpression(
    val left: Expression,
    val operatorSpan: Span,
    val operator: BinaryOperator,
    val right: Expression,
    override val span: Span,
) : Expression()

enum class BinaryOperator(val display: String) {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/"),
    REMAINDER("%"),
    EQUAL("=="),
    NOT_EQUAL("!="),
    LESS("<"),
    LESS_EQUAL("<="),
    GREATER(">"),
    GREATER_EQUAL(">="),
    AND("&&"),
    OR("||"),
}

/** `-operand` or `!operand` */
class UnaryExpression(
    val operatorSpan: Span,
    val operator: UnaryOperator,
    val operand: Expression,
    override val span: Span,
) : Expression()

enum class UnaryOperator(val display: String) { NEGATE("-"), NOT("!") }

/**
 * A chained comparison such as `a > b > c`, which means `a > b && b > c`.
 *
 * It gets its own node rather than being desugared into `&&` because each operand must be evaluated
 * *exactly once*: rewriting `a > b > c` as `(a > b) && (b > c)` would evaluate `b` twice, which is
 * observably wrong the moment an operand is a call.
 */
class ChainedComparisonExpression(
    /** The operands, in source order; always at least two. */
    val operands: List<Expression>,
    /** The operators between them; always exactly one fewer than [operands]. */
    val operators: List<BinaryOperator>,
    /** Span of each operator, so a type error can point at the operator that caused it. */
    val operatorSpans: List<Span>,
    override val span: Span,
) : Expression()

/** `callee(arguments)` */
class CallExpression(
    val callee: Expression,
    val arguments: List<Expression>,
    /** Span of the parenthesised argument list, including both parentheses. */
    val argumentListSpan: Span,
    override val span: Span,
    /**
     * Whether this is `callee?(arguments)` — a call that is skipped when the *function value* being
     * called is absent.
     *
     * This is the `?.` of a call rather than of a member. `obj?.f(x)` guards the *receiver*, because
     * the member is reached through it; `f?(x)` guards the *callee itself*, because a function value
     * held in a nullable variable is the thing that may not be there. The two answer a nullable value
     * for the same reason: a call that did not happen has no result.
     */
    val isSafeCall: Boolean = false,
) : Expression() {
    /**
     * Filled in by the analyzer when the call constructs a class rather than invoking a function.
     *
     * Neko has no `new`, so `CustomException()` — a capitalized name that is not a function — is a
     * construction. Deciding that here rather than in the parser keeps the grammar free of a
     * lookahead it could not resolve anyway.
     */
    var constructedType: ClassType? = null

    /**
     * Filled in by the analyzer: the declared constructor this construction calls, or `null` when
     * the class declares none and the default one is used.
     *
     * Knowing which overload was chosen is what lets the code generator pass the arguments with the
     * descriptors that overload declared, rather than with whatever types the arguments happen to
     * have — an `Object` parameter accepts a `string` and must be passed as one.
     */
    var resolvedConstructor: FunctionSymbol? = null

    /**
     * Filled in by the analyzer when the call invokes an *instance* member through `receiver.name`.
     *
     * The receiver expression is kept here rather than looked up again, because the code generator
     * must evaluate it exactly once, immediately before the arguments, to leave `this` on the stack
     * for the `invokevirtual`.
     */
    var instanceReceiver: Expression? = null

    /** Filled in by the analyzer: the non-static function this call invokes, if any. */
    var instanceMethod: FunctionSymbol? = null
}

/** `receiver.member`, or `receiver?.member` when [isSafeCall] is set. */
class MemberAccessExpression(
    val receiver: Expression,
    val memberName: String,
    val memberSpan: Span,
    override val span: Span,
    /**
     * `true` for `receiver?.member`, which evaluates to `null` rather than dereferencing when
     * [receiver] is null.
     *
     * The receiver is still a nullable type here: the safe call is what makes reading a member off
     * one legal, and the *result* becomes nullable because the member may not have been read at all.
     */
    val isSafeCall: Boolean = false,
) : Expression() {
    /**
     * Filled in by the analyzer: the field this access reads.
     *
     * `null` when the member is a function being *called* (see [CallExpression.instanceMethod]) or
     * when analysis failed.
     */
    var resolvedField: FieldSymbol? = null

    /**
     * Filled in by the analyzer: the instance function this access names as a value.
     *
     * A bare `obj.f` with no argument list is only meaningful for a field; the analyzer reports it
     * otherwise, but records the overload here so later passes stay consistent.
     */
    var resolvedFunction: FunctionSymbol? = null

    /**
     * The class this access names, when the receiver is a class name rather than a value.
     *
     * `Sample.field1` reaches a `static` field without an object; [receiverIsClass] records that so
     * the code generator emits `getstatic` alone instead of evaluating a receiver that is not a
     * value.
     */
    var receiverIsClass = false

    /**
     * The JVM owner of a **JDK** static field this access reads, or `null` for a program's own field.
     *
     * `System.out` is the case every program meets. It has no [FieldSymbol]: the program never
     * compiled `java.lang.System`, so there is no declaration to point at, and the emitter needs the
     * owner and descriptor it cannot derive from a symbol. The analyzer resolves it reflectively and
     * records the JVM name here, which is exactly the pair `getstatic` requires.
     *
     * Kept separate from [resolvedField] rather than folded into a synthetic symbol so that the
     * distinction stays visible: everything else in a member access is a Neko declaration.
     */
    var resolvedPlatformFieldOwner: String? = null

    /** `true` when [resolvedPlatformFieldOwner] names a `getstatic` rather than a `getfield`. */
    var resolvedPlatformFieldIsStatic = false
}

/** `this` — the object the enclosing instance member was invoked on. */
class ThisExpression(
    val keywordSpan: Span,
    override val span: Span,
) : Expression()

/**
 * `array[index]` — reads one element of an array.
 *
 * Arrays exist in Neko only as values that came from elsewhere (a function parameter, or a field
 * of a type another language declared); there is no array literal, so this is the only way to read
 * one element and [IndexAssignmentStatement] the only way to write one.
 */
class IndexExpression(
    val array: Expression,
    val index: Expression,
    /** Span of the whole `[index]` suffix, used when the index is the wrong type. */
    val indexSpan: Span,
    override val span: Span,
) : Expression()

/** `array[index] = value` */
class IndexAssignmentStatement(
    val array: Expression,
    val index: Expression,
    val value: Expression,
    val indexSpan: Span,
    override val span: Span,
) : Statement

/** A parameter of a lambda literal; its type may be inferred from the expected function type. */class LambdaParameter(
    val name: String,
    val nameSpan: Span,
    /** `null` when the parameter was written without a type annotation. */
    val declaredType: TypeReference?,
    override val span: Span,
) : Node {
    /** Filled in by the analyzer: the parameter's type, declared or inferred. */
    var resolvedType: NekoType? = null

    /** Filled in by the analyzer: the local this parameter introduces. */
    var symbol: LocalSymbol? = null
}

/** `{ x, y -> yield x + y }` */
class LambdaExpression(
    val parameters: List<LambdaParameter>,
    val body: BlockStatement,
    override val span: Span,
) : Expression() {
    /** Filled in by the analyzer: parameter types, in declaration order. */
    var resolvedParameterTypes: List<NekoType>? = null

    /** Filled in by the analyzer: the type produced by `yield`. */
    var resolvedReturnType: NekoType? = null

    /** Filled in by the analyzer: one symbol per lambda parameter. */
    var parameterSymbols: List<LocalSymbol>? = null

    /**
     * Filled in by the analyzer: the identity of this lambda literal.
     *
     * Each parameter and local declared inside this lambda records it as its `enclosingLambda`, so
     * the code generator can tell which references live inside this lambda (a local slot) from
     * which must be captured from an enclosing scope (a field).
     */
    var identity: Any? = null

    /**
     * Filled in by the analyzer: the single-method interface this lambda implements, if any.
     *
     * `null` when the lambda was written where a *function type* was expected, which is the only
     * shape the language had before. When it is set, the lambda is a value of that interface rather
     * than of a generated functional interface, and the code generator implements the interface's one
     * abstract method instead of a synthetic `invoke`. That is what makes `run({ ... })` work against
     * a parameter declared `Runnable`.
     *
     * Both fields are needed downstream: the class to implement, and the method's name — which is
     * `run` for `Runnable` and something else for every other interface, so it cannot be assumed.
     */
    var samInterface: SamTarget? = null
}

/** What a lambda has to implement to stand as a single-method interface. */
class SamTarget(
    /** JVM internal name of the interface, e.g. `java/lang/Runnable`. */
    val jvmName: String,
    /** The one abstract method's name, e.g. `run`. */
    val methodName: String,
    /** That method's parameter types, which the lambda's parameters are checked against. */
    val parameterTypes: List<NekoType>,
    /** What that method returns, which is what the lambda's `yield` has to produce. */
    val returnType: NekoType,
)
