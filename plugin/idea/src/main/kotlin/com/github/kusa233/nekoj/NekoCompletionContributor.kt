package com.github.kusa233.nekoj

import com.github.kusa233.neko.analyzer.BuiltinLibrary
import com.github.kusa233.neko.ast.NekoFile
import com.github.kusa233.neko.platform.PlatformLibrary
import com.github.kusa233.neko.symbols.ClassSymbol
import com.github.kusa233.neko.symbols.FieldSymbol
import com.github.kusa233.neko.symbols.FunctionSymbol
import com.github.kusa233.neko.types.BuiltinTypes
import com.github.kusa233.neko.types.ErrorType
import com.github.kusa233.neko.types.NekoType
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import java.nio.file.Path

/**
 * Offers completions for Neko source.
 *
 * The suggestions are not a hand-written list of names: they are the symbols the compiler's own
 * analyzer resolved for the project. So every class the reader has written, every field and
 * function on those classes, and the whole standard library show up — and a name appears only once
 * it actually exists, which is the same rule that decides whether the code compiles.
 *
 * Keywords are included because they are what a reader reaches for while writing a declaration
 * rather than an expression.
 */
class NekoCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            // Scoped to the *file*, not to the element's language. The extension point is already
            // registered for `language="Neko"`, so this contributor only ever runs in a Neko file;
            // adding `withLanguage` here would additionally demand that the element under the caret
            // be a Neko token, and the two most common places to ask for completion are inside
            // whitespace and after a comment -- both of which carry platform token types whose
            // language is `Language.ANY`, so completion would silently never appear there.
            PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(NekoPsiFile::class.java)),
            NekoCompletionProvider(),
        )
    }
}

private class NekoCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        // Keywords first: a fixed, small set that sharpens quickly as the reader types.
        for (keyword in NekoTokens.keywords.keys) {
            result.addElement(LookupElementBuilder.create(keyword).withTypeText("keyword", true))
        }

        // The standard library is available to every program without an import, so it is offered
        // even before the project has been analysed — and it is what a reader needs most often.
        for ((name, overloads) in BuiltinLibrary.functions()) {
            result.addElement(functionLookup(name, overloads, owner = null))
        }

        // Completion runs against the file being edited; the mock file used while a completion list
        // is being built is not ours, so the model is keyed off the real one.
        val file = parameters.originalFile as? NekoPsiFile ?: return
        val model = NekoProgramModel.forProject(file.project)

        // A type is a different vocabulary from a value, so a type position gets its own list: a
        // local variable or a method call is not something that can be written there.
        val ast = file.parsedFile(model)
        if (isTypePosition(file.text, parameters.offset)) {
            addTypeNames(model, ast, result)
            return
        }

        // `parameters.offset` is the caret in the *original* file, which is the text the model was
        // built from. `parameters.position` would be an offset into the throwaway copy the platform
        // makes for the completion, where every span in the tree below would be off by the length of
        // whatever the reader has typed since the last analysis.
        if (ast != null) {
            addNamesFromTheCurrentFile(ast, parameters.offset, result)
        }

        // The classes themselves, which is what a reader needs in order to name a type, construct an
        // object, or reach a `static` member through its class. The platform's own classes are
        // included, because an unqualified capitalized name is looked up there first.
        //
        // Their members are deliberately *not* listed here. A member is reached through a receiver
        // -- `obj.field`, or `Cls.field` for a static one -- so offering every class' members as bare
        // names would suggest calls like `helper()` that the analyzer rejects, since no such name is
        // in scope. The members a reader can actually write bare are the enclosing class' own, and
        // those come from `addNamesFromTheCurrentFile`.
        for (symbol in model.classes.values) {
            result.addElement(classLookup(symbol))
        }
        for ((name, packageName) in platformClasses(ast)) {
            result.addElement(platformClassLookup(name, packageName))
        }
    }

    /**
     * Offers the built-in types, the program's classes, and the Java platform's — everything a type
     * may name.
     *
     * The built-ins come from the compiler's own catalogue, so the list cannot drift from the names
     * the analyzer actually accepts, and each entry can say which Java reference type it is: that is
     * how a reader learns that `Integer` and `int` are the same type rather than two.
     */
    private fun addTypeNames(
        model: NekoProgramModel,
        ast: NekoFile?,
        result: CompletionResultSet,
    ) {
        for ((name, type) in BuiltinTypes.byName) {
            result.addElement(builtinTypeLookup(name, type))
        }
        for (symbol in model.classes.values) {
            result.addElement(classLookup(symbol))
        }
        for ((name, packageName) in platformClasses(ast)) {
            result.addElement(platformClassLookup(name, packageName))
        }
    }

    /**
     * Offers what the reader can name *here*: the locals in scope, and the members of the enclosing
     * class that this kind of body can reach.
     *
     * These are the names a reader most often wants and the ones a flat list of the program's
     * classes cannot give, because they depend on where the caret is. A parameter declared two lines
     * up, or the enclosing class' own helper, is worth more than the fourth class in some other
     * file — so they are also the ones the popup should show first, which is why they are added
     * before anything else that comes from the project.
     */
    private fun addNamesFromTheCurrentFile(
        ast: NekoFile,
        offset: Int,
        result: CompletionResultSet,
    ) {
        for (local in ast.localsVisibleAt(offset)) {
            result.addElement(localLookup(local))
        }

        // A `static` body reaches only its class' static members. An instance body reaches those
        // too -- a static member belongs to the class, not to an object -- and the instance members
        // as well, through the implicit `this`. Offering the instance members in a static body would
        // suggest a call the analyzer rejects, because there is no object to make it on, which is
        // the one thing a completion list must never do.
        val enclosing = ast.classAt(offset)?.symbol ?: return
        val staticContext = ast.functionAt(offset)?.isStatic ?: false

        for ((name, overloads) in enclosing.allFunctions()) {
            val reachable = overloads.filter { !staticContext || it.isStatic }
            if (reachable.isNotEmpty()) {
                result.addElement(functionLookup(name, reachable, owner = null))
            }
        }
        for ((name, field) in enclosing.allFields()) {
            if (!staticContext || field.isStatic) {
                result.addElement(fieldLookup(name, field, owner = null))
            }
        }
    }
}

/**
 * Whether the caret sits where a type is written.
 *
 * Decided by the text rather than by the syntax tree, because completion runs on a file the reader
 * is half-way through: `x: <caret>` does not parse into anything useful, and the tree the compiler
 * produced is that of the last complete analysis.
 *
 * Two positions count, and between them they cover every place Neko names a type:
 *
 *  * after a `:` — a local's or a field's type, a parameter's, a return type, and a supertype after
 *    `class X:`;
 *  * inside an `emit` clause's brackets, as in `} emit [int, <caret>]`.
 *
 * The name being typed is dropped first, since `x: in<caret>` is as much a type position as
 * `x: <caret>`; then the space after the punctuation that introduced it.
 *
 * A `->` is deliberately *not* treated as introducing a type, although a function type's return is
 * written after one. A `when` branch is written after one too and its body is a statement, so the
 * two cannot be told apart from the text alone — and offering types where a statement goes would be
 * worse than not offering them for a function type.
 */
private fun isTypePosition(text: String, offset: Int): Boolean {
    val before = text.substring(0, offset.coerceIn(0, text.length))
    val withoutPartialName = before.trimEnd().trimEnd { it.isLetterOrDigit() || it == '_' }
    val context = withoutPartialName.trimEnd()
    return context.endsWith(":") || insideEmitClause(context)
}

/**
 * Whether the caret is inside an `emit [ ... ]` list, given the text up to it.
 *
 * True when an `emit` keyword is followed by its `[` with no `]` in between — that is the region the
 * clause's own types live in. An ordinary bracket, such as a call's arguments or an array index,
 * has no `emit` before it and so is not a type position.
 */
private fun insideEmitClause(context: String): Boolean {
    val keyword = context.lastIndexOf("emit")
    if (keyword < 0) return false
    val open = context.indexOf('[', startIndex = keyword)
    if (open < 0) return false
    return context.indexOf(']', startIndex = open) < 0
}

/**
 * A built-in type, shown with the Java type it actually is.
 *
 * `int` is a JVM primitive, so a value of that type is carried as an `Integer` whenever it has to
 * be an `Object` — which is why `Integer` is a name this language accepts for the same type. Showing
 * the Java type is what makes that connection visible at the point where the reader is choosing.
 */
private fun builtinTypeLookup(name: String, type: NekoType): LookupElementBuilder {
    var builder = LookupElementBuilder.create(name)
    BuiltinTypes.referenceTypeNameOf(type)?.let { builder = builder.withTypeText(it, true) }
    BuiltinTypes.aliases[name]?.let { builder = builder.withTailText("  alias for $it", true) }
    return builder
}

/**
 * The names of the platform's classes that this file may write, without qualification.
 *
 * Two sets, because Neko resolves an unqualified capitalized name in two places: `java.lang` first,
 * and then the file's first imported package. Offering anything else would propose a name that
 * resolves to the wrong class or to none — the completion list has to describe this file, not the
 * JDK in general.
 *
 * [ast] is `null` when the file has no parse — while it is being written, say — and then only
 * `java.lang` is offered, since that lookup does not depend on the file.
 */
private fun platformClasses(ast: NekoFile?): List<Pair<String, String>> {
    val imported = ast?.imports?.firstOrNull()?.name
    val fromImport = if (imported == null) {
        emptyList()
    } else {
        PlatformLibrary.classesIn(imported).map { it to imported }
    }
    return PlatformLibrary.javaLangClasses.map { it to "java.lang" } + fromImport
}

/** A class the Java platform provides, shown with the package it belongs to. */
private fun platformClassLookup(name: String, packageName: String): LookupElementBuilder =
    LookupElementBuilder.create(name).withTypeText(packageName, true)

/** The parse of this file, or `null` when it was never parsed. */
private fun NekoPsiFile.parsedFile(model: NekoProgramModel): NekoFile? =
    model.fileOf(virtualFile?.path?.let { Path.of(it) })

/**
 * A local or a parameter, labelled as such.
 *
 * The two are worth telling apart: a parameter was handed to the reader by whoever called the
 * function, while a local is something they wrote themselves, and the second is much easier to
 * forget the name of.
 */
private fun localLookup(local: LocalName): LookupElementBuilder = LookupElementBuilder
    .create(local.name)
    .withTypeText(local.type?.let { typeLabel(it) }.orEmpty(), true)
    .withTailText(if (local.isParameter) "  parameter" else "  local", true)

private fun classLookup(symbol: ClassSymbol): LookupElementBuilder {
    val kind = if (symbol.isInterface) "interface" else "class"
    val parameters = symbol.typeParameters
    val tail = if (parameters.isEmpty()) "" else "<${parameters.joinToString(", ")}>"
    // Neko lets a class be named from any package without an import, so no package is shown.
    return LookupElementBuilder.create(symbol.name)
        .withTypeText(kind, true)
        .withTailText(tail, true)
}

private fun functionLookup(
    name: String,
    overloads: List<FunctionSymbol>,
    owner: String?,
): LookupElementBuilder {
    val first = overloads.firstOrNull()
    val type = first?.let { returnTypeOf(it) }.orEmpty()

    // The popup shows one row per name, so the extra overloads have to be mentioned rather than
    // listed: a reader who needs the other arities can ask for them by typing the arguments.
    val details = buildString {
        append("(")
        append(parametersOf(first))
        append(")")
        val extras = overloads.size - 1
        if (extras > 0) append("  +$extras more")
        if (owner != null) append("  in $owner")
    }

    return LookupElementBuilder.create(name)
        .withTypeText(type, true)
        .withTailText(details, true)
}

/**
 * A function's parameters as `name: type`, which is what tells one overload from another.
 *
 * The types are always shown and the names only when the compiler has them: a built-in such as
 * `println` is declared in code rather than in Neko source, so it has types but no parameter names,
 * and printing an empty name would read as `: int` rather than as `int`.
 */
private fun parametersOf(function: FunctionSymbol?): String {
    if (function == null) return ""
    val names = function.parameterNames
    return function.parameterTypes.mapIndexed { index, parameterType ->
        val label = typeLabel(parameterType)
        val name = names.getOrNull(index)
        if (name.isNullOrEmpty()) label else "$name: $label"
    }.joinToString(", ")
}

private fun fieldLookup(
    name: String,
    field: FieldSymbol,
    owner: String?,
): LookupElementBuilder = LookupElementBuilder.create(name)
    .withTypeText(typeLabel(field.type), true)
    .withTailText(if (owner == null) "" else "  in $owner", true)

/**
 * Renders a function's return type, plus its `emit` clause when it has one.
 *
 * The `emit` clause is spelled out because it changes how the function must be called: a function
 * that declares one may leave by raising instead of returning, and has to be invoked as
 * `try f(...)` so those values have somewhere to go. It can still return normally, which is exactly
 * why hiding the clause would mislead — the call is what the analyzer rejects, not the returning.
 */
private fun returnTypeOf(function: FunctionSymbol): String = buildString {
    append(typeLabel(function.returnType))
    if (function.hasEmitClause) {
        append(" emit ")
        append(function.emitTypes.joinToString(", ", "[", "]") { typeLabel(it) })
    }
}

/**
 * A type's name, without the compiler's error marker.
 *
 * When the analyzer cannot work out a type it stores an error type, whose display name is a
 * complaint rather than a name; showing that in a completion popup would be noise.
 */
private fun typeLabel(type: NekoType): String =
    if (type is ErrorType) "?" else type.displayName
