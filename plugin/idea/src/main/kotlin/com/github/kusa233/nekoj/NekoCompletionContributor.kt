package com.github.kusa233.nekoj

import com.github.kusa233.neko.analyzer.BuiltinLibrary
import com.github.kusa233.neko.symbols.ClassSymbol
import com.github.kusa233.neko.symbols.FieldSymbol
import com.github.kusa233.neko.symbols.FunctionSymbol
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
        for (symbol in model.classes.values) {
            result.addElement(classLookup(symbol))
            for ((name, overloads) in symbol.allFunctions()) {
                result.addElement(functionLookup(name, overloads, owner = symbol.name))
            }
            for ((name, field) in symbol.allFields()) {
                result.addElement(fieldLookup(name, field, owner = symbol.name))
            }
        }
    }
}

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
        append(first?.parameterNames?.joinToString(", ").orEmpty())
        append(")")
        val extras = overloads.size - 1
        if (extras > 0) append("  +$extras more")
        if (owner != null) append("  in $owner")
    }

    return LookupElementBuilder.create(name)
        .withTypeText(type, true)
        .withTailText(details, true)
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
 * that declares one never returns a value and has to be invoked as `try f(...)`, so hiding it would
 * suggest a call the analyzer rejects.
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
