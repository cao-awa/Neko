package com.github.kusa233.neko.ast

import com.github.kusa233.neko.source.Span
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
     */
    fun importPrefixes(): List<String> = imports.map { it.name.replace('.', '/') + "/" }
}

/**
 * `import com.github.kusa233.neko.stdlib.emit`
 *
 * Names a package whose classes this file may refer to by simple name. It is not a per-class alias
 * and it never hides a declaration: resolution still prefers this file's own package and the
 * program's own declarations, so an import can only ever *add* a way to resolve a name.
 */
class ImportDeclaration(
    /** Fully-qualified package name, e.g. `com.github.kusa233.neko.stdlib.emit`. */
    val name: String,
    /** Individual dotted segments, used to point at one segment in diagnostics. */
    val segments: List<String>,
    override val span: Span,
) : Node

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
    override val span: Span,
) : Node {
    /** Filled in by the analyzer. */
    var symbol: ClassSymbol? = null
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
     * A function with an `emit` clause gains a second way out: a `raise a, b` statement inside it
     * transfers control to the caller's `try` extractor, carrying one value per listed type,
     * instead of returning normally. The paths that do not raise still return a value of the
     * declared return type, so the clause says "may raise", not "never returns". The clause is
     * written after the body.
     */
    val emitTypes: List<TypeReference> = emptyList(),
    /**
     * `true` when an `emit` clause was written at all.
     *
     * Distinct from `emitTypes.isNotEmpty()`, because `emit []` is a legal clause naming zero
     * values: a `raise` with nothing to carry, matched by the caller's `try f() { -> ... }`.
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
class NamedTypeReference(val name: String, val nameSpan: Span, override val span: Span) : TypeReference

/** `string[]` */
class ArrayTypeReference(val element: TypeReference, override val span: Span) : TypeReference

/** `[int, int] -> int` */
class FunctionTypeReference(
    val parameters: List<TypeReference>,
    val returnType: TypeReference,
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

/** `yield x + y` — the value produced by a lambda body. */
class YieldStatement(
    val keywordSpan: Span,
    val value: Expression,
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
 * `raise e1, e2` — leaves the enclosing `emit`-declaring function early, handing the values to the
 * caller's extractor.
 *
 * Only valid inside a function that declares an `emit [...]` clause, and the number of values must
 * match the number of types in that clause. Execution never continues after a `raise`.
 */
class RaiseStatement(
    val keywordSpan: Span,
    val values: List<Expression>,
    override val span: Span,
) : Statement {
    /**
     * Filled in by the code generator: the static builder method that boxes the raised values.
     *
     * The sample names this `test$emit$0()` — one per `raise` site, named after its function.
     */
    var builderName: String? = null

    /** Filled in by the code generator: the class that declares [builderName]. */
    var builderOwner: String? = null
}

/**
 * `try { ... } catch (e: T) { ... } finally { ... }`.
 *
 * This is how an Neko program handles a throwable raised by code the language did not compile —
 * another JVM language's library method, or an exception the JVM itself throws. Neko has no
 * `throw` of its own: the only way this language transfers control abnormally is `raise`, which is
 * caught by the extractor form of `try` rather than by a `catch` clause.
 *
 * Both `catch` and `finally` are optional, but a `try` must have at least one of them — a bare
 * `try { ... }` protects nothing and is rejected by the analyzer.
 */
class TryCatchStatement(
    val keywordSpan: Span,
    val body: BlockStatement,
    /** One or more `catch` clauses, in the order they were written. */
    val catches: List<CatchClause>,
    /** The `finally` block, or `null` when none was written. */
    val finallyBlock: BlockStatement?,
    /** Span of the whole finally clause, used when reporting a malformed one. */
    val finallySpan: Span?,
    override val span: Span,
) : Statement

/** One `catch (e: T) { ... }` clause of a [TryCatchStatement]. */
class CatchClause(
    val parameterName: String,
    val parameterNameSpan: Span,
    /** The throwable type this clause handles. */
    val declaredType: TypeReference,
    val body: BlockStatement,
    override val span: Span,
) : Node {
    /** Filled in by the analyzer: the resolved throwable type, which must extend `Throwable`. */
    var resolvedType: NekoType? = null

    /** Filled in by the analyzer: the local that names the caught value inside [body]. */
    var symbol: LocalSymbol? = null
}

/**
 * The part of a `try` that catches a `raise`, shared by the statement and the expression form.
 *
 * Both forms call a function that may `raise` and run the same kind of extractor over the values it
 * carries; they differ only in what the construct is worth. `try f(...) { a -> ... }` on its own is a
 * statement and produces nothing, while `r: int = try f(...) { a -> yield 1 }` is an expression whose
 * value is what its extractor `yield`s — which is why [acceptsYield] distinguishes the two and why
 * only the expression form may write `yield`.
 */
interface ExtractorTry : Node {
    /** Span of the `try` keyword. */
    val keywordSpan: Span

    /** The call whose `raise` this `try` catches. Always a [CallExpression]. */
    val call: CallExpression

    /** The names given to the raised values, in declaration order. */
    val extractorParameters: List<LambdaParameter>

    val extractorBody: BlockStatement

    /** Span of the `->` that introduces the extractor body, used when it is missing. */
    val arrowSpan: Span?

    /** `true` for the expression form, whose extractor produces the value the `try` is worth. */
    val acceptsYield: Boolean

    /**
     * The type the extractor's `yield` produces, fixed by the first one it writes.
     *
     * `null` means the body never `yield`s, so its `invoke` returns nothing and the `try` uses the
     * void `EmitExtractorN` family. Analysis fills this in, and code generation reads it to pick the
     * family — which is why it is the one property that decides the lowering.
     */
    var yieldedType: NekoType?
}

/**
 * `try call(args) { a, b -> ... }` — calls a function that may `raise`, and runs [extractorBody] with
 * the raised values if it does.
 *
 * This is the only construct that catches a `raise`. [extractorParameters] receive the raised values
 * in declaration order; their types come from the callee's `emit` clause, so a lambda-style
 * parameter list needs no annotations. A call that returns normally never runs the extractor body.
 *
 * A call to a function with an `emit` clause is *only* legal here — or in [TryExpression], which asks
 * the same call for a value: anywhere else it is an error, because such a call cannot return a value
 * and its `raise` would have nowhere to go.
 */
class TryStatement(
    override val keywordSpan: Span,
    /** The call whose `raise` this statement catches. Always a [CallExpression]. */
    override val call: CallExpression,
    override val extractorParameters: List<LambdaParameter>,
    override val extractorBody: BlockStatement,
    /** Span of the `->` that introduces the extractor body, used when it is missing. */
    override val arrowSpan: Span?,
    override val span: Span,
) : Statement, ExtractorTry {
    /** A statement is worth nothing, so its extractor has no value to `yield`. */
    override val acceptsYield: Boolean get() = false

    /** A statement's extractor can never `yield`, so this stays `null`. */
    override var yieldedType: NekoType? = null
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
 * `r: int = try f(...) { a -> yield 1 }` — the same catching construct as [TryStatement], but worth a
 * value.
 *
 * When the call returns normally the expression is worth whatever it returned; when the call
 * `raise`s instead, the extractor runs and the expression is worth the value its `yield` produced.
 * That is why a `yield` is what the extractor body needs in order to be a value at all: without one
 * the extractor answers nothing, and a reader who wrote `r: int = try ...` would silently get
 * nothing. The analyzer requires one whenever the value is actually read.
 */
class TryExpression(
    override val keywordSpan: Span,
    override val call: CallExpression,
    override val extractorParameters: List<LambdaParameter>,
    override val extractorBody: BlockStatement,
    override val arrowSpan: Span?,
    override val span: Span,
) : Expression(), ExtractorTry {
    /** An expression is worth a value, so its extractor may `yield` one. */
    override val acceptsYield: Boolean get() = true

    override var yieldedType: NekoType? = null
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

class IntegerLiteral(val value: Long, override val span: Span) : Expression()

class StringLiteral(val value: String, override val span: Span) : Expression()

class BoolLiteral(val value: Boolean, override val span: Span) : Expression()

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

/** `receiver.member` — reads a field or names an instance function of [receiver]. */
class MemberAccessExpression(
    val receiver: Expression,
    val memberName: String,
    val memberSpan: Span,
    override val span: Span,
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
}
