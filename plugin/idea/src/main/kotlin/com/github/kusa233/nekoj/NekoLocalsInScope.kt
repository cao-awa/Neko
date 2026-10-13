package com.github.kusa233.nekoj

import com.github.kusa233.neko.ast.BlockStatement
import com.github.kusa233.neko.ast.CatchClause
import com.github.kusa233.neko.ast.ClassDeclaration
import com.github.kusa233.neko.ast.ConstructorDeclaration
import com.github.kusa233.neko.ast.FunctionDeclaration
import com.github.kusa233.neko.ast.IfStatement
import com.github.kusa233.neko.ast.LambdaExpression
import com.github.kusa233.neko.ast.NekoFile
import com.github.kusa233.neko.ast.Statement
import com.github.kusa233.neko.ast.TryCatchStatement
import com.github.kusa233.neko.ast.TryStatement
import com.github.kusa233.neko.ast.VariableDeclarationStatement
import com.github.kusa233.neko.ast.WhenStatement
import com.github.kusa233.neko.ast.WhileStatement
import com.github.kusa233.neko.source.Span
import com.github.kusa233.neko.types.NekoType

/** A name the reader can write at some point in a file, and what it holds. */
class LocalName(
    val name: String,
    /** `null` when the analyzer could not work the type out, e.g. because the program has errors. */
    val type: NekoType?,
    /** `true` for a parameter — of a function, a lambda, an extractor or a `catch` — rather than a
     * variable the reader declared in the body. */
    val isParameter: Boolean,
)

/**
 * The locals in scope at [offset], outermost first.
 *
 * A reader part-way through an expression wants the names around them — the parameter they were
 * handed, the variable they declared a few lines up — far more often than they want a list of every
 * class in the program. The compiler knows which names are in scope, but only while it is analysing;
 * the IDE has to ask at an arbitrary point mid-edit, which is what this works out.
 *
 * It walks the syntax tree rather than consulting the analyzer's symbol table, because the analyzer
 * resolves scopes as it goes and keeps no record of where each one ended. The tree, which is kept
 * whole, says exactly that: a declaration is visible from its own span onwards, inside the block
 * that holds it and every block nested within.
 *
 * A name is reported only when it is declared *before* [offset]. Offering a variable declared
 * further down the file would suggest the reader can use it now, which the analyzer would then
 * reject — the one thing completion must not do.
 */
fun NekoFile.localsVisibleAt(offset: Int): List<LocalName> {
    val found = LinkedHashMap<String, LocalName>()
    for (declaration in classes) {
        if (!declaration.span.holds(offset)) continue
        for (constructor in declaration.constructors) {
            collectFromConstructor(constructor, offset, found)
        }
        for (function in declaration.functions) {
            collectFromFunction(function, offset, found)
        }
    }
    return found.values.toList()
}

/** The class whose span holds [offset], or `null` when the caret sits between declarations. */
fun NekoFile.classAt(offset: Int): ClassDeclaration? =
    classes.firstOrNull { it.span.holds(offset) }

/**
 * The function whose span holds [offset], or `null` when the caret is outside every body.
 *
 * Completion needs this to tell a `static` body from an instance one: the two can see different
 * members of their own class, and offering the wrong half would suggest code the analyzer rejects.
 */
fun NekoFile.functionAt(offset: Int): FunctionDeclaration? = classes
    .asSequence()
    .filter { it.span.holds(offset) }
    .flatMap { it.functions.asSequence() }
    .firstOrNull { it.span.holds(offset) }

private fun collectFromFunction(
    function: FunctionDeclaration,
    offset: Int,
    into: MutableMap<String, LocalName>,
) {
    if (!function.span.holds(offset)) return

    // A parameter is in scope for the whole body, so it goes in before anything the body declares.
    // The types are absent when analysis did not run or failed, which leaves the name showing
    // without a type rather than not showing at all.
    for ((parameter, type) in function.parameters.zip(function.resolvedParameterTypes.orEmpty())) {
        into[parameter.name] = LocalName(parameter.name, type, isParameter = true)
    }
    function.body?.let { collectStatements(it.statements, offset, into) }
}

private fun collectFromConstructor(
    constructor: ConstructorDeclaration,
    offset: Int,
    into: MutableMap<String, LocalName>,
) {
    if (!constructor.span.holds(offset)) return

    for (symbol in constructor.parameterSymbols) {
        into[symbol.name] = LocalName(symbol.name, symbol.type, isParameter = true)
    }
    collectStatements(constructor.body.statements, offset, into)
}

/**
 * Adds every declaration in [statements] that is in scope at [offset].
 *
 * The walk stops at the first statement that *starts* at or after the caret: statements are in
 * source order, so nothing past that point can be in scope yet. Containers holding the caret are
 * descended into, which is what confines a variable declared inside an `if` to that `if`.
 */
private fun collectStatements(
    statements: List<Statement>,
    offset: Int,
    into: MutableMap<String, LocalName>,
) {
    for (statement in statements) {
        if (statement.span.start >= offset) return

        when (statement) {
            is VariableDeclarationStatement -> {
                into[statement.name] = LocalName(statement.name, statement.resolvedType, false)
                // A lambda initializer introduces its own parameters for its own body.
                val initializer = statement.initializer
                if (initializer is LambdaExpression) collectFromLambda(initializer, offset, into)
            }

            is BlockStatement -> descend(statement, offset, into)

            is IfStatement -> {
                descend(statement.thenBranch, offset, into)
                descend(statement.elseBranch, offset, into)
            }

            is WhileStatement -> descend(statement.body, offset, into)

            is WhenStatement -> for (branch in statement.branches) {
                descend(branch.body, offset, into)
            }

            is TryCatchStatement -> {
                descend(statement.body, offset, into)
                for (clause in statement.catches) collectFromCatch(clause, offset, into)
                descend(statement.finallyBlock, offset, into)
            }

            is TryStatement -> {
                // `try f() { a, b -> ... }` names the raised values for its extractor body alone.
                if (!statement.extractorBody.span.holds(offset)) continue
                for (parameter in statement.extractorParameters) {
                    into[parameter.name] =
                        LocalName(parameter.name, parameter.resolvedType, isParameter = true)
                }
                collectStatements(statement.extractorBody.statements, offset, into)
            }

            else -> Unit
        }
    }
}

/** Descends into [statement] when it holds the caret; a bare declaration is handled as itself. */
private fun descend(
    statement: Statement?,
    offset: Int,
    into: MutableMap<String, LocalName>,
) {
    if (statement == null || !statement.span.holds(offset)) return
    if (statement is BlockStatement) {
        collectStatements(statement.statements, offset, into)
    } else {
        collectStatements(listOf(statement), offset, into)
    }
}

private fun collectFromCatch(
    clause: CatchClause,
    offset: Int,
    into: MutableMap<String, LocalName>,
) {
    if (!clause.span.holds(offset)) return
    into[clause.parameterName] =
        LocalName(clause.parameterName, clause.resolvedType, isParameter = true)
    collectStatements(clause.body.statements, offset, into)
}

private fun collectFromLambda(
    lambda: LambdaExpression,
    offset: Int,
    into: MutableMap<String, LocalName>,
) {
    if (!lambda.span.holds(offset)) return
    for (symbol in lambda.parameterSymbols.orEmpty()) {
        into[symbol.name] = LocalName(symbol.name, symbol.type, isParameter = true)
    }
    collectStatements(lambda.body.statements, offset, into)
}

/** Whether [offset] falls inside this span. An empty span holds nothing. */
private fun Span.holds(offset: Int): Boolean = offset >= start && offset < end
