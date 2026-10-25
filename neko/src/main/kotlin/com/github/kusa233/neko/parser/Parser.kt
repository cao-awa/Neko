package com.github.kusa233.neko.parser

import com.github.kusa233.neko.ast.*
import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.lexer.Lexer
import com.github.kusa233.neko.lexer.Token
import com.github.kusa233.neko.lexer.TokenType
import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.source.Span

/**
 * A recursive-descent parser for Neko that produces an [NekoFile].
 *
 * Two properties matter for the diagnostics requirement:
 *
 *  * **Precision.** Every node and every reported error carries the [Span] of the exact token that
 *    caused it, so an error points at `str` rather than at line 16 as a whole.
 *  * **Recovery.** On error the parser reports and then synchronises to the next statement or
 *    member boundary instead of aborting. One bad line therefore yields one clear diagnostic rather
 *    than a cascade of confusing follow-on errors.
 *
 * ### Newline handling
 *
 * Neko is newline-sensitive (as Kotlin is): a physical line break ends a statement. Two contexts
 * deliberately ignore newlines — parenthesised and bracketed lists, so that argument lists and
 * parameter lists may wrap — while a lambda *body* always restores newline significance, because
 * its `{ ... }` holds ordinary statements.
 */
class Parser(private val source: SourceFile, private val diagnostics: DiagnosticBag) {

    private val tokens: List<Token>
    private var index = 0

    /** See [skipNestedNewlines]: greater than zero while inside `(` or `[`. */
    private var newlineInsensitiveDepth = 0

    init {
        val lexerDiagnostics = DiagnosticBag()
        tokens = Lexer(source, lexerDiagnostics).lex()
        diagnostics.addAll(lexerDiagnostics)
    }

    /** Parses the whole file. */
    fun parseFile(): NekoFile {
        val first = current()
        val packageDeclaration = parsePackageDeclaration()
        skipNewlines()

        val imports = mutableListOf<ImportDeclaration>()
        // Imports come between `locate` and the first declaration. They are collected in a loop
        // rather than required in one run so that a missing `locate` still reports its own error
        // first, and so `import` remains a keyword the statement parser can point at.
        while (at(TokenType.IMPORT)) {
            parseImportDeclaration()?.let { imports += it }
            skipNewlines()
        }

        val classes = mutableListOf<ClassDeclaration>()
        while (!at(TokenType.END_OF_FILE)) {
            val before = index
            parseClassDeclaration()?.let { classes += it }
            if (index == before) advance() // Guarantee progress on malformed input.
            skipNewlines()
        }

        val last = current()
        return NekoFile(
            packageDeclaration = packageDeclaration,
            imports = imports,
            classes = classes,
            span = Span(source, first.span.start, last.span.end),
        )
    }

    // ------------------------------------------------------------- declarations

    /** `locate com.github.kusa233.neko.stdlib.emit` */
    private fun parsePackageDeclaration(): PackageDeclaration? {
        if (!at(TokenType.LOCATE)) return null
        val locateToken = advance()
        skipNewlines()

        if (!isNameSegment(current())) {
            error(
                "expected a package name after 'locate' but found ${current().describe()}",
                current().span,
            )
            return null
        }

        val (name, nameSpan) = parseDottedName(locateToken, "a package name segment")
        return PackageDeclaration(name, name.split('.'), nameSpan)
    }

    /**
     * `import java.util` or `import java.util.HashMap`
     *
     * Both are the same syntax: a dotted name. Which one it is is decided by the analyzer, because
     * only the platform can say whether the last segment is a class or a package part — `java.util`
     * is a package, `java.util.HashMap` is a class, and no rule about the shape of the text
     * distinguishes them. The parser therefore reads the name and records it; see
     * [ImportDeclaration.isClassImport].
     *
     * `.*` is deliberately not accepted. It would be a second spelling of what a bare `import
     * java.util` already means, and it is reported with the form to write instead.
     */
    private fun parseImportDeclaration(): ImportDeclaration? {
        val keyword = advance() // `import`
        skipNewlines()

        if (!isNameSegment(current())) {
            error(
                "expected a package or class name after 'import' but found ${current().describe()}",
                current().span,
            )
            synchronizeToStatement()
            return null
        }

        val (name, nameSpan) = parseDottedName(keyword, "an imported name segment")

        // `import java.util.*` — the wildcard. Reported here rather than at analysis because it is a
        // syntax the language does not have, and the fix is a rewrite of this clause, not a change
        // somewhere else. The span covers the `.*` so the caret lands on the part to delete.
        if (at(TokenType.DOT) && peek(1).type == TokenType.STAR) {
            val dot = advance()
            val star = advance()
            error(
                "Neko has no '.*': 'import $name' already imports everything in '$name', so write " +
                    "'import $name'",
                Span(source, dot.span.start, star.span.end),
            )
            return ImportDeclaration(name, name.split('.'), nameSpan)
        }

        return ImportDeclaration(name, name.split('.'), nameSpan)
    }

    /**
     * Reads a dotted name, e.g. `com.github.kusa233.neko.stdlib.emit`.
     *
     * Shared by `locate` and `import`, which are the two places a fully-qualified package name
     * appears, so both accept the same segments and report the same way. [owner] is the keyword token
     * the span starts from when the name turns out to be empty.
     */
    private fun parseDottedName(owner: Token, expected: String): Pair<String, Span> {
        val segments = mutableListOf<String>()
        var startOffset = -1
        var endOffset = owner.span.end
        while (true) {
            if (!isNameSegment(current())) {
                error("expected $expected but found ${current().describe()}", current().span)
                break
            }
            val segment = advance()
            if (startOffset < 0) startOffset = segment.span.start
            endOffset = segment.span.end
            segments += segment.text
            if (at(TokenType.DOT)) {
                // `.*` is not a segment: the `.` is left for the caller, which reports the wildcard
                // with the form to write instead. Consuming it here would turn `java.util.*` into two
                // errors — "expected a segment" and whatever follows — instead of the one that says
                // what to do.
                if (peek(1).type == TokenType.STAR) break
                advance()
                continue
            }
            break
        }

        val span = Span(source, if (startOffset < 0) owner.span.start else startOffset, endOffset)
        return segments.joinToString(".") to span
    }

    /**
     * Whether [token] can stand as one dotted-name segment.
     *
     * A keyword is accepted here: a package name is a name, not a use of the language's grammar, and
     * the standard library lives in `com.github.kusa233.neko.stdlib.emit`. Reserved words stay
     * reserved everywhere a *statement* or *declaration* is expected — this leniency is confined to
     * the dotted name after `locate` and `import`, where no keyword could mean anything else.
     */
    private fun isNameSegment(token: Token): Boolean =
        token.type == TokenType.IDENTIFIER || token.type in NAME_SEGMENT_KEYWORDS

    /** What a class member declaration looks like once its modifiers are skipped. */
    private enum class MemberKind { FIELD, FUNCTION, CONSTRUCTOR, STATIC_BLOCK, UNKNOWN }

    private fun memberKindAhead(): MemberKind {
        var offset = 0
        var sawStatic = false
        while (isModifier(peek(offset).type)) {
            if (peek(offset).type == TokenType.STATIC) sawStatic = true
            offset++
        }
        return when (peek(offset).type) {
            TokenType.VAL, TokenType.VAR -> MemberKind.FIELD
            TokenType.FUN -> MemberKind.FUNCTION
            TokenType.CONSTRUCTOR -> MemberKind.CONSTRUCTOR
            // `static { ... }`. Any modifier written alongside `static` is reported by the caller:
            // `private static {` is not a declaration this grammar has, but recognising it here is
            // what lets the error say so instead of complaining about an unexpected `private`.
            TokenType.LEFT_BRACE ->
                if (sawStatic) MemberKind.STATIC_BLOCK else MemberKind.UNKNOWN

            else -> MemberKind.UNKNOWN
        }
    }

    /**
     * The `static` modifier written directly on a single member, which is no longer how a static
     * member is declared.
     *
     * Returning it rather than reporting it here lets the caller decide. What the caller does is
     * report the spelling and then parse the member as static anyway: `static fun f()` and `fun f()`
     * inside a block mean the same thing, so a reader who has not caught up should see one error
     * about how it is written, not a cascade about what it means.
     */
    private fun staticModifierAhead(): Modifier? {
        var offset = 0
        while (isModifier(peek(offset).type)) {
            val token = peek(offset)
            if (token.type == TokenType.STATIC) return Modifier(ModifierKind.STATIC, token.span)
            offset++
        }
        return null
    }

    private fun isModifier(type: TokenType): Boolean =
        type == TokenType.PRIVATE || type == TokenType.PUBLIC ||
            type == TokenType.PROTECTED || type == TokenType.STATIC

    /** `private class Sample { ... }`, `interface EmitExtractor<T> { ... }`, `meow class Neko(...)` */
    private fun parseClassDeclaration(): ClassDeclaration? {
        val start = current()
        val modifiers = parseModifiers()

        // `meow` marks a class whose fields are declared in its header, so it is read before the
        // `class`/`interface` choice: the two words together are what the declaration is, and a
        // `meow interface` has to be told apart from a plain one in order to be reported.
        //
        // `miao` is read in the same place for the same reason, and the two are mutually exclusive:
        // a class is either described by its fields or enumerated by its constants, and asking for
        // both would leave the header meaning two different things.
        val prefixToken = when {
            at(TokenType.MEOW) -> advance()
            at(TokenType.MIAO) -> advance()
            else -> null
        }
        val isMiao = prefixToken?.type == TokenType.MIAO
        val meowToken = prefixToken?.takeIf { it.type == TokenType.MEOW }

        val isInterface = when {
            at(TokenType.CLASS) -> false
            at(TokenType.INTERFACE) -> true
            prefixToken != null -> {
                val word = prefixToken.text
                error(
                    "'$word' declares a class whose ${if (isMiao) "constants are" else "fields are"} " +
                        "written in its ${if (isMiao) "body" else "header"}, so it must be followed by " +
                        "'class'; write '$word className...'",
                    current().span,
                )
                synchronizeToDeclaration()
                return null
            }

            else -> {
                error(
                    "expected a class declaration but found ${current().describe()}; " +
                        "only class and interface declarations may appear at the top level of an " +
                        "Neko file",
                    current().span,
                )
                synchronizeToDeclaration()
                return null
            }
        }
        // `isMeow` is carried to the analyzer rather than reported here, so that a `meow interface`
        // is still parsed as the interface it looks like and its body's own mistakes surface in the
        // same run as the one the reader has to fix first. The same goes for `miao interface`.
        val isMeow = meowToken != null
        val keyword = advance()
        val kindWord = if (isInterface) "interface" else "class"

        val nameToken = expect(TokenType.IDENTIFIER, "expected a $kindWord name but found ${current().describe()}")
            ?: run {
                synchronizeToDeclaration()
                return null
            }

        // Type parameters, erased to `Object` at run time: `class Box<T>`.
        val typeParameters = mutableListOf<String>()
        if (at(TokenType.LESS)) {
            advance()
            skipNestedNewlines()
            while (true) {
                skipNestedNewlines()
                val parameter = expect(
                    TokenType.IDENTIFIER,
                    "expected a type parameter name but found ${current().describe()}",
                ) ?: break
                if (typeParameters.any { it == parameter.text }) {
                    error("duplicate type parameter '${parameter.text}'", parameter.span)
                } else {
                    typeParameters += parameter.text
                }
                skipNestedNewlines()
                if (at(TokenType.COMMA)) {
                    advance()
                    continue
                }
                break
            }
            skipNestedNewlines()
            expect(
                TokenType.GREATER,
                "expected '>' to close the type parameters of $kindWord '${nameToken.text}' but " +
                    "found ${current().describe()}",
            )
        }

        // A `meow class` states its fields here, and the list is the whole point of the form: it is
        // parsed into the fields and a constructor below. A `miao class` states them here for the
        // same reason — they are the types of the arguments its constants are written with — though
        // the constructor that takes them is emitted by the compiler rather than from a body. A
        // plain class may write an empty list — as in `class ExceptionSample()` — but any parameter
        // in it is rejected rather than silently ignored, because its constructor is the one
        // declared in the body.
        val meowFields = mutableListOf<FieldDeclaration>()
        var meowConstructor: ConstructorDeclaration? = null
        val headerParameters = at(TokenType.LEFT_PAREN)
        if (headerParameters) {
            val open = advance()
            if (isMeow || isMiao) {
                newlineInsensitiveDepth++
                skipNestedNewlines()
                if (!at(TokenType.RIGHT_PAREN)) {
                    while (true) {
                        parseMeowField()?.let { meowFields += it }
                        skipNestedNewlines()
                        if (at(TokenType.COMMA)) {
                            advance()
                            skipNestedNewlines()
                            continue
                        }
                        break
                    }
                }
                skipNestedNewlines()
                val close = expectRightParen("'$kindWord ${nameToken.text}'")
                newlineInsensitiveDepth--
                // An enum's fields are written by each constant's own arguments, so there is no
                // source constructor to synthesize: only a `meow` class stores its header fields
                // from parameters the reader passes in.
                if (isMeow) {
                    meowConstructor = buildMeowConstructor(
                        fields = meowFields,
                        openOffset = open.span.start,
                        closeOffset = close?.span?.end ?: open.span.end,
                    )
                }
            } else {
                skipNestedNewlines()
                if (!at(TokenType.RIGHT_PAREN)) {
                    error(
                        "$kindWord '${nameToken.text}' cannot declare constructor parameters here: " +
                            "declare a 'constructor(...)' in the body instead",
                        current().span,
                    )
                    // Recover by skipping to the closing parenthesis, so the body still parses.
                    var depth = 1
                    while (depth > 0 && !at(TokenType.END_OF_FILE)) {
                        when (advance().type) {
                            TokenType.LEFT_PAREN -> depth++
                            TokenType.RIGHT_PAREN -> depth--
                            else -> {}
                        }
                    }
                } else {
                    advance()
                }
            }
        } else if (isMeow) {
            error(
                "a 'meow' class must list its fields, because that list is what its 'equals' and " +
                    "'hashCode' are derived from; write 'meow class ${nameToken.text}()' when it " +
                    "has none",
                current().span,
            )
        }

        // Supertypes: `: Superclass(), Interface, Interface<T>`.
        var superclass: SuperTypeReference? = null
        val interfaces = mutableListOf<SuperTypeReference>()
        if (at(TokenType.COLON)) {
            advance()
            while (true) {
                skipNewlines()
                val name = expect(
                    TokenType.IDENTIFIER,
                    "expected a supertype name but found ${current().describe()}",
                ) ?: break

                var arguments: List<Expression>? = null
                var end = name.span.end
                if (at(TokenType.LEFT_PAREN)) {
                    advance()
                    newlineInsensitiveDepth++
                    val parsed = mutableListOf<Expression>()
                    skipNestedNewlines()
                    if (!at(TokenType.RIGHT_PAREN)) {
                        while (true) {
                            val argument = parseExpression() ?: break
                            parsed += argument
                            skipNestedNewlines()
                            if (at(TokenType.COMMA)) {
                                advance()
                                skipNestedNewlines()
                                continue
                            }
                            break
                        }
                    }
                    skipNestedNewlines()
                    val close = expectRightParen("supertype '$name'")
                    newlineInsensitiveDepth--
                    arguments = parsed
                    end = close?.span?.end ?: name.span.end
                }

                // Generic arguments written as `EmitExtractor<T>` or `Iterable<int>`: captured rather
                // than skipped, because `for ... in` needs to know what an iterable yields. They are
                // still erased from the class file — nothing downstream of the analyzer reads them.
                var typeArguments: List<TypeReference> = emptyList()
                if (at(TokenType.LESS)) {
                    val saveArguments = index
                    val parsedArguments = parseTypeArguments()
                    if (parsedArguments.isNotEmpty()) {
                        typeArguments = parsedArguments
                        end = parsedArguments.last().span.end
                    } else {
                        // Not a type argument list after all (a stray `<`), so leave it for whatever
                        // comes next to report.
                        index = saveArguments
                        var depth = 0
                        while (!at(TokenType.END_OF_FILE)) {
                            val token = advance()
                            if (token.type == TokenType.LESS) depth++
                            if (token.type == TokenType.GREATER) {
                                depth--
                                if (depth == 0) {
                                    end = token.span.end
                                    break
                                }
                            }
                        }
                    }
                }

                val reference = SuperTypeReference(
                    name = name.text,
                    nameSpan = name.span,
                    arguments = arguments,
                    typeArguments = typeArguments,
                    span = Span(source, name.span.start, end),
                )

                if (arguments != null) {
                    // A constructor call means a superclass, and a class may only extend one.
                    if (superclass != null) {
                        error(
                            "$kindWord '${nameToken.text}' already extends " +
                                "'${superclass.name}', so it cannot also extend '${name.text}'; a " +
                                "class may have only one superclass",
                            name.span,
                        )
                    } else {
                        superclass = reference
                    }
                } else {
                    interfaces += reference
                }

                if (at(TokenType.COMMA)) {
                    advance()
                    continue
                }
                break
            }
        }

        if (!expectLeftBrace(kindWord + " '${nameToken.text}'")) {
            synchronizeToDeclaration()
            return null
        }

        val fields = mutableListOf<FieldDeclaration>()
        val functions = mutableListOf<FunctionDeclaration>()
        val constructors = mutableListOf<ConstructorDeclaration>()

        // The header's fields and constructor come first, so a member written in the body is analyzed
        // after the ones the header declared — which is the order the source reads in, and what makes
        // a duplicate between the two report the header's declaration as the previous one.
        fields += meowFields
        meowConstructor?.let { constructors += it }

        // A `miao class` names its constants first, because they are what the declaration is about:
        // everything after them is behaviour the constants share. Reading them before the member loop
        // is also what keeps the two apart — an entry is a bare name, which the member loop would
        // otherwise have to report as a field that forgot its `val`.
        val entries = if (isMiao) parseEnumEntries(nameToken) else emptyList()

        skipNewlines()
        while (!at(TokenType.RIGHT_BRACE) && !at(TokenType.END_OF_FILE)) {
            val before = index
            when (memberKindAhead()) {
                MemberKind.FUNCTION -> {
                    reportStaticOnASingleMember()
                    parseFunctionDeclaration()?.let { functions += it }
                }

                MemberKind.FIELD -> {
                    reportStaticOnASingleMember()
                    parseFieldDeclaration(isInterface, nameToken)?.let { fields += it }
                }

                MemberKind.CONSTRUCTOR -> parseConstructorDeclaration(nameToken)?.let { constructors += it }
                MemberKind.STATIC_BLOCK -> {
                    reportModifierOnStaticBlock()
                    parseStaticMemberBlock(isInterface, nameToken, fields, functions)
                }
                MemberKind.UNKNOWN -> {
                    error(
                        "expected a field, function or constructor declaration in $kindWord " +
                            "'${nameToken.text}' but found ${current().describe()}",
                        current().span,
                    )
                    synchronizeToMember()
                }
            }
            if (index == before) advance()
            skipNewlines()
        }

        val closing = expectRightBrace("$kindWord '${nameToken.text}'")
        val end = closing?.span?.end ?: nameToken.span.end

        return ClassDeclaration(
            name = nameToken.text,
            nameSpan = nameToken.span,
            typeParameters = typeParameters,
            modifiers = modifiers,
            superclass = superclass,
            interfaces = interfaces,
            fields = fields,
            constructors = constructors,
            functions = functions,
            isInterface = isInterface,
            isMeow = isMeow,
            isMiao = isMiao,
            entries = entries,
            span = Span(source, keyword.span.start, end),
        )
    }

    /**
     * The constants of a `miao class`: `RED, GREEN, BLUE` or `OK(200), GONE(410)`.
     *
     * The list runs until something that cannot be an entry begins — a `val`, a `fun`, a
     * `constructor`, a `static` block or the closing `}`. That is what lets the constants and the
     * members after them share one brace-delimited body without a separator between the two, which
     * is how it reads: the constants first, then what they all share.
     *
     * The trailing `;` is accepted and dropped, so a reader used to writing one is not punished for
     * it, and an empty list is accepted here so the diagnostic about an enum with no constants can
     * be the analyzer's single message rather than a parse failure.
     */
    private fun parseEnumEntries(className: Token): List<EnumEntry> {
        val entries = mutableListOf<EnumEntry>()

        skipNewlines()
        if (at(TokenType.RIGHT_BRACE) || at(TokenType.END_OF_FILE)) return entries

        while (true) {
            skipNewlines()
            // Anything that starts a member is where the constants ended. Whether the list was empty
            // is the analyzer's to report, since it is a fact about the declaration rather than about
            // how the text parsed.
            if (memberKindAhead() != MemberKind.UNKNOWN || at(TokenType.RIGHT_BRACE)) break

            val nameToken = expect(
                TokenType.IDENTIFIER,
                "expected the name of a constant of '${className.text}' but found " +
                    current().describe(),
            ) ?: run {
                synchronizeToMember()
                break
            }

            var end = nameToken.span.end
            val arguments = mutableListOf<Expression>()
            if (at(TokenType.LEFT_PAREN)) {
                advance()
                newlineInsensitiveDepth++
                skipNestedNewlines()
                if (!at(TokenType.RIGHT_PAREN)) {
                    while (true) {
                        val argument = parseExpression() ?: break
                        arguments += argument
                        skipNestedNewlines()
                        if (at(TokenType.COMMA)) {
                            advance()
                            skipNestedNewlines()
                            continue
                        }
                        break
                    }
                }
                skipNestedNewlines()
                val close = expectRightParen("constant '$nameToken'")
                newlineInsensitiveDepth--
                end = close?.span?.end ?: finalArgumentEnd(arguments, nameToken)
            }

            entries += EnumEntry(
                name = nameToken.text,
                nameSpan = nameToken.span,
                arguments = arguments,
                span = Span(source, nameToken.span.start, end),
            )

            skipNewlines()
            if (at(TokenType.COMMA)) {
                advance()
                continue
            }
            // A `;` closes the constant list explicitly, as it does in Java. Without one the members
            // simply follow the last constant.
            if (at(TokenType.SEMICOLON)) advance()
            break
        }

        return entries
    }

    /** Where a constant ends when its `)` was missing, so the node still has a span to report on. */
    private fun finalArgumentEnd(
        arguments: List<Expression>,
        nameToken: Token,
    ): Int = arguments.lastOrNull()?.span?.end ?: nameToken.span.end

    /**
     * Reports `static` written on a single member, which is no longer how static members are
     * declared.
     *
     * The member is still parsed as static afterwards, because that is what the reader meant and
     * `parseModifiers` has already recorded it. Reporting the spelling and keeping the meaning is
     * what keeps this to one diagnostic.
     */
    private fun reportStaticOnASingleMember() {
        val modifier = staticModifierAhead() ?: return
        error(
            "a member cannot be marked 'static' on its own; a static member is written inside a " +
                "'static { ... }' block, where every member is static",
            modifier.span,
        )
    }

    /**
     * Reports a modifier written on a `static { ... }` block itself.
     *
     * Visibility belongs on the members. A block is a grouping, so a `private` on it would state
     * once what each member can already state for itself — and the two could then disagree, with no
     * rule for which wins.
     */
    private fun reportModifierOnStaticBlock() {
        if (at(TokenType.STATIC)) return
        error(
            "a 'static' block takes no modifier; write the visibility on the members inside it, " +
                "as in 'private val count: int = 0'",
            current().span,
        )
    }

    /**
     * `static { private val field1: int = 123 \n fun main(args: string[]) { ... } }`
     *
     * The block is a grouping, not a construct of its own: every member inside it is parsed exactly
     * as it would be outside and then handed the `static` modifier the block supplies. Nothing
     * downstream therefore has to know a block was written — the analyzer and the emitter keep
     * asking each member whether it is static, and the answer is already in its modifiers.
     *
     * A block cannot nest and cannot hold a constructor. Nesting would only repeat what the outer
     * block already says, and a constructor builds an instance, so `static` is not a property it
     * could have.
     */
    private fun parseStaticMemberBlock(
        isInterface: Boolean,
        classToken: Token,
        fields: MutableList<FieldDeclaration>,
        functions: MutableList<FunctionDeclaration>,
    ) {
        // A modifier before `static` has already been reported; skipping it here keeps the block
        // parsing, so the reader sees one error about the spelling rather than a lost declaration.
        while (!at(TokenType.STATIC) && isModifier(current().type)) advance()

        val keyword = advance() // `static`
        val staticModifier = Modifier(ModifierKind.STATIC, keyword.span)

        if (!expectLeftBrace("static block")) {
            synchronizeToMember()
            return
        }

        skipNewlines()
        while (!at(TokenType.RIGHT_BRACE) && !at(TokenType.END_OF_FILE)) {
            val before = index
            when (memberKindAhead()) {
                MemberKind.FUNCTION ->
                    parseFunctionDeclaration(staticModifier)?.let { functions += it }

                MemberKind.FIELD ->
                    parseFieldDeclaration(isInterface, classToken, staticModifier)?.let { fields += it }

                MemberKind.CONSTRUCTOR -> {
                    error(
                        "a 'static' block cannot declare a constructor: a constructor builds an " +
                            "instance, so it is never static",
                        current().span,
                    )
                    synchronizeToMember()
                }

                MemberKind.STATIC_BLOCK -> {
                    error(
                        "this 'static' block is already inside a 'static' block; every member here " +
                            "is static already",
                        current().span,
                    )
                    // Parsed anyway, with the same modifier, so the members inside are still
                    // declarations rather than a second cascade of errors.
                    parseStaticMemberBlock(isInterface, classToken, fields, functions)
                }

                MemberKind.UNKNOWN -> {
                    error(
                        "expected a field or function declaration in this 'static' block but found " +
                            current().describe(),
                        current().span,
                    )
                    synchronizeToMember()
                }
            }
            if (index == before) advance()
            skipNewlines()
        }

        expectRightBrace("static block")
    }

    /**
     * `constructor(data: Object[]) { ... }`
     *
     * A constructor has no name of its own — it is always called through the class — so the
     * parameters and body are all it declares. Neko gives it no return type for the same reason.
     */
    private fun parseConstructorDeclaration(classToken: Token): ConstructorDeclaration? {
        val start = current()
        val modifiers = parseModifiers()
        advance() // `constructor`

        if (!expectLeftParen("constructor")) {
            synchronizeToMember()
            return null
        }
        val parameters = parseParameterList()
        expectRightParen("constructor")

        if (!at(TokenType.LEFT_BRACE)) {
            error(
                "expected '{' to start the body of the constructor but found ${current().describe()}",
                current().span,
            )
            synchronizeToMember()
            return null
        }
        val body = parseBlock() ?: run {
            synchronizeToMember()
            return null
        }

        return ConstructorDeclaration(
            parameters = parameters,
            body = body,
            modifiers = modifiers,
            span = Span(source, start.span.start, body.span.end),
        )
    }

    /**
     * `private val field1: int = 123`
     *
     * An interface may not declare instance fields — only static ones — which is checked here
     * rather than in the analyzer so the error points at the field itself.
     *
     * [staticFromBlock] is the modifier an enclosing `static { ... }` supplies, or `null` when the
     * field stands directly in the class body.
     */
    private fun parseFieldDeclaration(
        isInterface: Boolean,
        classToken: Token,
        staticFromBlock: Modifier? = null,
    ): FieldDeclaration? {
        val start = current()
        val modifiers = parseModifiers() + listOfNotNull(staticFromBlock)

        val kindToken = current()
        val kind = when (kindToken.type) {
            TokenType.VAL -> VariableKind.VAL
            TokenType.VAR -> VariableKind.VAR
            else -> {
                error("expected 'val' or 'var' but found ${kindToken.describe()}", kindToken.span)
                synchronizeToMember()
                return null
            }
        }
        advance()

        val nameToken = expect(TokenType.IDENTIFIER, "expected a field name but found ${current().describe()}")
            ?: run {
                synchronizeToMember()
                return null
            }

        if (!expectColon("field '${nameToken.text}'")) {
            synchronizeToMember()
            return null
        }

        val type = parseType() ?: run {
            synchronizeToMember()
            return null
        }

        var initializer: Expression? = null
        var end = type.span.end
        if (at(TokenType.ASSIGN)) {
            advance()
            initializer = parseExpression()
            if (initializer != null) end = initializer.span.end
        }

        expectStatementEnd("field declaration")

        if (isInterface && !modifiers.any { it.kind == ModifierKind.STATIC }) {
            error(
                "interface '${classToken.text}' cannot declare the instance field " +
                    "'${nameToken.text}': an interface has no state, so a field belongs in a " +
                    "'static { ... }' block",
                nameToken.span,
            )
        }

        return FieldDeclaration(
            kind = kind,
            kindSpan = kindToken.span,
            name = nameToken.text,
            nameSpan = nameToken.span,
            declaredType = type,
            initializer = initializer,
            modifiers = modifiers,
            span = Span(source, start.span.start, end),
        )
    }

    /**
     * One entry of a `meow class` header: `[private|protected|public] val|var name: Type`.
     *
     * The entry is *only* a declaration. The value it names arrives as a constructor parameter, so no
     * initializer is read here and an `= value` written in the header is left for the caller's
     * comma/`)` check to report rather than being given a second meaning.
     *
     * Returns `null` after reporting, having consumed to the next entry's boundary, so the rest of
     * the list still becomes the declarations it looks like.
     */
    private fun parseMeowField(): FieldDeclaration? {
        val start = current()
        val modifiers = parseModifiers()

        val kindToken = current()
        val kind = when (kindToken.type) {
            TokenType.VAL -> VariableKind.VAL
            TokenType.VAR -> VariableKind.VAR
            else -> {
                error(
                    "expected 'val' or 'var' to say what this 'meow' field is but found " +
                        kindToken.describe(),
                    kindToken.span,
                )
                synchronizeToMeowField()
                return null
            }
        }
        advance()

        val nameToken = expect(
            TokenType.IDENTIFIER,
            "expected a 'meow' field name but found ${current().describe()}",
        ) ?: run {
            synchronizeToMeowField()
            return null
        }

        if (!expectColon("'meow' field '${nameToken.text}'")) {
            synchronizeToMeowField()
            return null
        }

        val type = parseType() ?: run {
            synchronizeToMeowField()
            return null
        }

        return FieldDeclaration(
            kind = kind,
            kindSpan = kindToken.span,
            name = nameToken.text,
            nameSpan = nameToken.span,
            declaredType = type,
            // A header field is never initialized where it is declared: the constructor that takes
            // it as a parameter is the only thing that writes it.
            initializer = null,
            modifiers = modifiers,
            span = Span(source, start.span.start, type.span.end),
        )
    }

    /**
     * The constructor a `meow class` header implies: one parameter per field, each stored into it.
     *
     * The body is written here as the source `this.name = name` rather than lowered later, so the
     * analyzer and the code generator handle a meow constructor with the code they already have —
     * the standard library's `EarlyEmitException` stores its array with exactly that statement.
     * Nothing downstream therefore needs to know where the body came from.
     *
     * The parameter list is the fields in order, so the values land in the fields the header named.
     */
    private fun buildMeowConstructor(
        fields: List<FieldDeclaration>,
        openOffset: Int,
        closeOffset: Int,
    ): ConstructorDeclaration {
        val assignments = fields.map { field ->
            // The statement is not in the source, so it is anchored at the field it stores: a
            // diagnostic about this class' `val` fields should point at `val x: int`, not at the
            // `(` that happens to be where the constructor was inferred from.
            val assignmentSpan = field.span
            AssignmentStatement(
                target = MemberAccessExpression(
                    receiver = ThisExpression(assignmentSpan, assignmentSpan),
                    memberName = field.name,
                    memberSpan = field.nameSpan,
                    span = assignmentSpan,
                ),
                value = NameExpression(field.name, field.nameSpan, assignmentSpan),
                span = assignmentSpan,
            )
        }

        val body = BlockStatement(statements = assignments, span = Span(source, openOffset, closeOffset))

        return ConstructorDeclaration(
            parameters = fields.map { field ->
                Parameter(
                    name = field.name,
                    nameSpan = field.nameSpan,
                    declaredType = field.declaredType,
                    span = field.span,
                )
            },
            body = body,
            // A meow constructor is the class' own and is written nowhere in the source: it takes no
            // visibility of its own, so the header's modifiers stay on the fields they were written
            // in front of.
            modifiers = emptyList(),
            span = body.span,
        )
    }

    /**
     * `fun main(args: string[]) { ... }`
     *
     * [staticFromBlock] is the modifier an enclosing `static { ... }` supplies, or `null` when the
     * function stands directly in the class body.
     */
    private fun parseFunctionDeclaration(staticFromBlock: Modifier? = null): FunctionDeclaration? {
        val start = current()
        val modifiers = parseModifiers() + listOfNotNull(staticFromBlock)

        if (!at(TokenType.FUN)) {
            error("expected 'fun' but found ${current().describe()}", current().span)
            synchronizeToMember()
            return null
        }
        advance()

        val nameToken = expect(TokenType.IDENTIFIER, "expected a function name but found ${current().describe()}")
            ?: run {
                synchronizeToMember()
                return null
            }

        if (!expectLeftParen("function '${nameToken.text}'")) {
            synchronizeToMember()
            return null
        }

        val parameters = parseParameterList()
        expectRightParen("parameter list of function '${nameToken.text}'")

        var returnType: TypeReference? = null
        if (at(TokenType.COLON)) {
            advance()
            returnType = parseType()
        }

        // A function may be abstract — a signature an implementer must provide — which is written by
        // omitting the body entirely. That is only meaningful inside an interface, but the body is
        // optional here and the analyzer decides whether the omission is legal, so the diagnostic can
        // name the function and the class it was found in.
        var body: BlockStatement? = null
        if (at(TokenType.LEFT_BRACE)) {
            body = parseBlock() ?: run {
                synchronizeToMember()
                return null
            }
        } else if (!atStatementEnd() && !at(TokenType.RIGHT_BRACE) && !at(TokenType.EMIT)) {
            error(
                "expected '{' to start the body of function '${nameToken.text}' but found " +
                    current().describe(),
                current().span,
            )
            synchronizeToMember()
            return null
        }

        // An `emit` clause follows the body: `fun f() { ... } emit [int, int]`.
        val emitClause = parseEmitClause()
        val end = emitClause?.endOffset ?: body?.span?.end ?: returnType?.span?.end ?: nameToken.span.end

        return FunctionDeclaration(
            name = nameToken.text,
            nameSpan = nameToken.span,
            parameters = parameters,
            returnType = returnType,
            body = body,
            modifiers = modifiers,
            emitTypes = emitClause?.types.orEmpty(),
            hasEmitClause = emitClause != null,
            span = Span(source, start.span.start, end),
        )
    }

    /** A parsed `emit [T, U]` clause: its value types, and where the clause ends. */
    private class EmitClause(val types: List<TypeReference>, val endOffset: Int)

    /**
     * Parses an optional `emit [T, U]` clause written after a function body.
     *
     * The brackets are required and may be empty, so `emit []` is a clause that names no values — a
     * function that can only signal failure. Returns `null` when no clause is present.
     */
    private fun parseEmitClause(): EmitClause? {
        val mark = index
        skipNewlines()
        if (!at(TokenType.EMIT)) {
            index = mark
            return null
        }
        val keyword = advance()

        if (!expectLeftBracket("'emit' clause")) {
            return EmitClause(emptyList(), keyword.span.end)
        }
        newlineInsensitiveDepth++

        val types = mutableListOf<TypeReference>()
        skipNestedNewlines()
        if (!at(TokenType.RIGHT_BRACKET)) {
            while (true) {
                val type = parseType() ?: break
                types += type
                skipNestedNewlines()
                if (at(TokenType.COMMA)) {
                    advance()
                    skipNestedNewlines()
                    continue
                }
                break
            }
        }

        skipNestedNewlines()
        val close = expectRightBracket("'emit' clause")
        newlineInsensitiveDepth--
        return EmitClause(types, close?.span?.end ?: keyword.span.end)
    }

    /** `(a: int, b: string)`; the opening parenthesis is consumed by the caller. */
    private fun parseParameterList(): List<Parameter> {
        val parameters = mutableListOf<Parameter>()
        if (at(TokenType.RIGHT_PAREN)) return parameters

        while (true) {
            skipNestedNewlines()
            val nameToken = expect(
                TokenType.IDENTIFIER,
                "expected a parameter name but found ${current().describe()}",
            ) ?: break

            if (!expectColon("parameter '${nameToken.text}'")) break
            val type = parseType() ?: break

            parameters += Parameter(
                name = nameToken.text,
                nameSpan = nameToken.span,
                declaredType = type,
                span = Span(source, nameToken.span.start, type.span.end),
            )

            skipNestedNewlines()
            if (at(TokenType.COMMA)) {
                advance()
                continue
            }
            break
        }
        return parameters
    }

    private fun parseModifiers(): List<Modifier> {
        val modifiers = mutableListOf<Modifier>()
        while (true) {
            val kind = when (current().type) {
                TokenType.PRIVATE -> ModifierKind.PRIVATE
                TokenType.PUBLIC -> ModifierKind.PUBLIC
                TokenType.PROTECTED -> ModifierKind.PROTECTED
                TokenType.STATIC -> ModifierKind.STATIC
                else -> return modifiers
            }
            val token = advance()
            if (modifiers.any { it.kind == kind }) {
                error("duplicate modifier '${kind.display}'", token.span)
            } else {
                modifiers += Modifier(kind, token.span)
            }
        }
    }

    // -------------------------------------------------------------- statements

    /** `{ ... }` */
    private fun parseBlock(): BlockStatement? {
        val open = expectLeftBraceReturning("block") ?: run {
            synchronizeToMember()
            return null
        }

        val statements = parseStatementSequence()
        val close = expectRightBrace("block")
        val end = close?.span?.end ?: current().span.end
        return BlockStatement(statements, Span(source, open.span.start, end))
    }

    /**
     * Parses statements until the matching `}` or end of file.
     *
     * Always treats newlines as significant, which is what makes a lambda body behave like any
     * other block no matter how deeply it is nested inside a call's argument list.
     */
    private fun parseStatementSequence(): List<Statement> {
        val statements = mutableListOf<Statement>()
        val savedDepth = newlineInsensitiveDepth
        newlineInsensitiveDepth = 0
        try {
            skipNewlines()
            while (!at(TokenType.RIGHT_BRACE) && !at(TokenType.END_OF_FILE)) {
                val before = index
                if (!rejectRemovedThrow()) parseStatement()?.let { statements += it }
                if (index == before) advance()
                skipNewlines()
            }
        } finally {
            newlineInsensitiveDepth = savedDepth
        }
        return statements
    }

    /** Parses one statement, or returns `null` after reporting an error. */
    private fun parseStatement(): Statement? = when (current().type) {
        TokenType.LEFT_BRACE -> parseBlock()
        TokenType.VAL, TokenType.VAR -> parseVariableDeclaration()
        TokenType.RETURN -> parseReturnStatement()
        TokenType.YIELD -> parseYieldStatement()
        TokenType.IF -> parseIfStatement()
        TokenType.WHILE -> parseWhileStatement()
        TokenType.FOR -> parseForStatement()
        TokenType.PLUS_PLUS, TokenType.MINUS_MINUS -> parseIncrementStatement()
        TokenType.WHEN -> parseWhenStatement()
        TokenType.NYA -> parseNyaStatementForm()
        TokenType.NYAN -> parseNyanStatement()
        TokenType.TRY -> parseTryStatement()
        TokenType.PURR, TokenType.PURRED,
        TokenType.PURRCLS, TokenType.PURREDCLS,
        -> parsePurrStatement()
        TokenType.SEMICOLON -> {
            advance()
            null
        }

        TokenType.IDENTIFIER ->
            // `name: type = ...` declares; `name = ...` assigns.
            if (peek(1).type == TokenType.COLON) parseVariableDeclaration() else parseAssignmentOrExpression()

        else -> parseAssignmentOrExpression()
    }

    /**
     * Reports a `throw`/`throws`/`raise` as the removed keyword it is, rather than as an unknown name.
     *
     * None of the three is a keyword any more, so `throw X()` would otherwise be read as a call and
     * reported as "there is no function named 'throw'" — true, but it leaves the reader guessing.
     * Recognising the word here, at the start of a statement, is what keeps the diagnostic actionable
     * while the language deliberately treats them as ordinary identifiers everywhere else.
     *
     * `raise` is the interesting one: it was the word `scratch` replaced, and the two do not mean the
     * same thing, so pointing at `scratch` without saying why would be misleading. It says what
     * changed — `scratch` is a call that returns, not a departure — so a program ported from `raise`
     * fails with an explanation rather than with a silent change of meaning.
     *
     * Returns true when a diagnostic was reported, so the caller stops before parsing on.
     */
    private fun rejectRemovedThrow(): Boolean {
        val token = current()
        if (token.type != TokenType.IDENTIFIER) return false
        if (token.text != "throw" && token.text != "throws" && token.text != "raise") return false

        val replacement = when (token.text) {
            "throw" -> "Neko enters a 'try' handler with 'scratch <value>, ...' and leaves it with " +
                "'nyan <value>'; to let a JVM exception out, there is nothing to write — an " +
                "uncaught exception already propagates"
            "raise" -> "'scratch <value>, ...' replaced it, but the meaning changed: a 'scratch' " +
                "calls the handler and carries on with its answer, so it does not leave the " +
                "function. Write 'nyan <value>' inside the handler to answer the site, or " +
                "'yield <value>' to leave through the 'try'"
            else -> "Neko records that a function can reach a 'try' handler in its 'emit [...]' " +
                "clause rather than in a 'throws' clause, and a function that writes 'scratch' " +
                "always declares one"
        }
        diagnostics.error("'${token.text}' is not an Neko keyword: $replacement", token.span)
        synchronizeToStatement()
        return true
    }

    /**
     * A local declaration: `val a: int = 1`, `a: int = 1`, or `var a: int`.
     *
     * The leading `val`/`var` is optional in Neko, which is why `str: string = "..."` and
     * `str = "new value"` look alike until the `:` or the `=` is reached.
     */
    private fun parseVariableDeclaration(): VariableDeclarationStatement? {
        val start = current()
        // A declaration without `val`/`var` is mutable. `Sample.neko` pins this down: `str: string`
        // on line 12 is later reassigned by `str = "new value"`, which the sample marks as valid.
        var kind = VariableKind.VAR
        var kindSpan: Span? = null

        if (at(TokenType.VAL) || at(TokenType.VAR)) {
            val keyword = advance()
            kind = if (keyword.type == TokenType.VAL) VariableKind.VAL else VariableKind.VAR
            kindSpan = keyword.span
        }

        val nameToken = expect(TokenType.IDENTIFIER, "expected a variable name but found ${current().describe()}")
            ?: run {
                synchronizeToStatement()
                return null
            }

        if (!expectColon("variable '${nameToken.text}'")) {
            synchronizeToStatement()
            return null
        }

        val type = parseType() ?: run {
            synchronizeToStatement()
            return null
        }

        var initializer: Expression? = null
        var end = type.span.end
        if (at(TokenType.ASSIGN)) {
            advance()
            skipNestedNewlines()
            initializer = parseExpression()
            if (initializer != null) end = initializer.span.end
        }

        expectStatementEnd("variable declaration")

        return VariableDeclarationStatement(
            kind = kind,
            kindSpan = kindSpan,
            name = nameToken.text,
            nameSpan = nameToken.span,
            declaredType = type,
            initializer = initializer,
            span = Span(source, start.span.start, end),
        )
    }

    /** `name = value`, or an expression statement such as `println(field1)`. */
    private fun parseAssignmentOrExpression(): Statement? {
        val start = current()
        val expression = parseExpression() ?: run {
            synchronizeToStatement()
            return null
        }

        // `i++` and `holder.count++`. The suffix is read here rather than by the expression parser
        // because `++` is a statement, not an expression: it produces no value, so it cannot appear
        // wherever a value is expected. Recognising it after a complete expression is what makes the
        // trailing spelling work, and the leading spelling is handled in `parseIncrementStatement`.
        if (at(TokenType.PLUS_PLUS) || at(TokenType.MINUS_MINUS)) {
            val operator = advance()
            expectStatementEnd("'${operator.text}'")
            val member = expression as? MemberAccessExpression
            val target = when (expression) {
                is NameExpression -> expression
                is MemberAccessExpression -> NameExpression(
                    expression.memberName,
                    expression.memberSpan,
                    expression.span,
                )

                else -> {
                    error(
                        "'${operator.text}' applies to a variable or a field, but found " +
                            current().describe(),
                        expression.span,
                    )
                    return null
                }
            }
            val statement = IncrementStatement(
                operator = operator.text,
                target = target,
                operatorSpan = operator.span,
                span = Span(source, start.span.start, operator.span.end),
            )
            // A member target keeps its receiver, so `holder.count++` reaches the field through
            // `holder` rather than treating `count` as a name in this method's scope.
            statement.memberTarget = member
            return statement
        }

        if (at(TokenType.ASSIGN)) {
            advance()
            // A name assigns a local; a member access assigns a field through its receiver; an index
            // assigns one element of an array.
            if (expression is IndexExpression) {
                skipNestedNewlines()
                val value = parseExpression() ?: run {
                    synchronizeToStatement()
                    return null
                }
                expectStatementEnd("assignment")
                return IndexAssignmentStatement(
                    array = expression.array,
                    index = expression.index,
                    value = value,
                    indexSpan = expression.indexSpan,
                    span = Span(source, start.span.start, value.span.end),
                )
            }
            if (expression !is NameExpression && expression !is MemberAccessExpression) {
                error(
                    "the left-hand side of an assignment must be a variable name, a field access " +
                        "or an array element",
                    expression.span,
                )
                parseExpression()
                expectStatementEnd("assignment")
                return null
            }
            skipNestedNewlines()
            val value = parseExpression() ?: run {
                synchronizeToStatement()
                return null
            }
            expectStatementEnd("assignment")
            return AssignmentStatement(
                target = expression,
                value = value,
                span = Span(source, start.span.start, value.span.end),
            )
        }

        expectStatementEnd("expression")
        return ExpressionStatement(expression, expression.span)
    }

    /** `return` or `return expression` */
    private fun parseReturnStatement(): ReturnStatement {
        val keyword = advance()
        var value: Expression? = null
        var end = keyword.span.end
        if (!atStatementEnd()) {
            value = parseExpression()
            if (value != null) end = value.span.end
        }
        expectStatementEnd("return statement")
        return ReturnStatement(keyword.span, value, Span(source, keyword.span.start, end))
    }

    /** `yield expression` */
    /**
     * `yield` on its own, or `yield value`.
     *
     * The value is optional because a `yield` may answer nothing: a handler that abandons a call whose
     * `try` is worth nothing has nothing to carry. Whether the form is right for the place it is
     * written depends on what the surrounding construct wants, which is the analyzer's call.
     */
    private fun parseYieldStatement(): YieldStatement? {
        val keyword = advance()
        if (atStatementEnd()) {
            // A bare `yield` answers nothing at all.
            return YieldStatement(keyword.span, value = null, span = keyword.span)
        }
        val value = parseExpression() ?: run {
            synchronizeToStatement()
            return null
        }
        expectStatementEnd("yield statement")
        return YieldStatement(keyword.span, value, Span(source, keyword.span.start, value.span.end))
    }

    /** `if (condition) statement else statement` */
    private fun parseIfStatement(): IfStatement? {
        val keyword = advance()
        val condition = parseParenthesised("if") ?: run {
            synchronizeToStatement()
            return null
        }

        skipNewlines()
        val thenBranch = parseStatement() ?: run {
            synchronizeToStatement()
            return null
        }

        // `else` may sit on the same line or on the following one.
        val mark = index
        skipNewlines()
        var elseBranch: Statement? = null
        var end = thenBranch.span.end
        if (at(TokenType.ELSE)) {
            advance()
            skipNewlines()
            elseBranch = parseStatement()
            if (elseBranch != null) end = elseBranch.span.end
        } else {
            index = mark
        }

        return IfStatement(condition, thenBranch, elseBranch, Span(source, keyword.span.start, end))
    }

    /**
     * `i++` — a statement that adds one to a variable in place.
     *
     * The target has to be a bare name. `a[i]++` and `f()++` are refused because neither is a variable:
     * there would be no slot to write the result back to, and the value the expression produced would
     * be discarded, which is never what the reader meant.
     */
    private fun parseIncrementStatement(): Statement? {
        val operator = advance()
        val target = current()
        if (target.type != TokenType.IDENTIFIER) {
            error("'${operator.text}' applies to a variable, but found ${target.describe()}", target.span)
            synchronizeToStatement()
            return null
        }
        advance()
        expectStatementEnd("'${operator.text}'")
        return IncrementStatement(
            operator = operator.text,
            target = NameExpression(target.text, target.span, target.span),
            operatorSpan = operator.span,
            span = Span(source, operator.span.start, target.span.end),
        )
    }

    /** `while (condition) statement` */
    private fun parseWhileStatement(): WhileStatement? {
        val keyword = advance()
        val condition = parseParenthesised("while") ?: run {
            synchronizeToStatement()
            return null
        }
        skipNewlines()
        val body = parseStatement() ?: run {
            synchronizeToStatement()
            return null
        }
        return WhileStatement(condition, body, Span(source, keyword.span.start, body.span.end))
    }

    /**
     * `for (i: int = 0; i < 100; i++) { ... }`, or `for (element: int in list) { ... }`.
     *
     * The two forms agree up to the declared type: both write `name: type`, and what follows decides
     * which loop this is. `in` means for-each, and that is the whole reason `in` is a keyword — seeing
     * it is what tells the two apart without guessing, since an ordinary three-part `for` also has a
     * name and a type first.
     *
     * The three-part form parses its header inside the loop's own scope: the declared variable must be
     * visible to the condition and the update, and must stop being visible after the body.
     */
    private fun parseForStatement(): Statement? {
        val keyword = advance()
        if (!expectLeftParen("'for'")) {
            synchronizeToStatement()
            return null
        }
        skipNewlines()

        // Both forms declare their variable with `name: type`, so the two are told apart by the token
        // after the type rather than by trying one parse and backing out of it.
        val looksLikeDeclaration = at(TokenType.IDENTIFIER) && peek(1).type == TokenType.COLON
        if (looksLikeDeclaration) {
            // The look-ahead is undone by rewinding [index]: a `for` header is a handful of tokens, so
            // re-reading it costs nothing and keeps one parse path per form.
            val save = index
            val nameToken = advance()
            advance() // the colon
            val type = parseType()
            if (type != null && at(TokenType.IN)) {
                return parseForEachRest(keyword, nameToken, type)
            }
            // Not a for-each, so it was an ordinary declaration after all and the header parser wants
            // to read the `=` itself.
            index = save
        }

        return parseForLoopRest(keyword)
    }

    /**
     * The initializer of a three-part `for`, which a `;` ends rather than a newline.
     *
     * This mirrors `parseVariableDeclaration` but stops before the `;`: a declaration's own parser
     * insists on a newline at the end of the statement, and inside a `for` header the separator is the
     * semicolon instead. Sharing the code is not possible without teaching that parser about two
     * different terminators, so the small amount of duplication is deliberate.
     */
    private fun parseForInitializer(): VariableDeclarationStatement? {
        val start = current()
        var kind = VariableKind.VAR
        var kindSpan: Span? = null
        if (at(TokenType.VAL) || at(TokenType.VAR)) {
            val keyword = advance()
            kind = if (keyword.type == TokenType.VAL) VariableKind.VAL else VariableKind.VAR
            kindSpan = keyword.span
        }

        val nameToken = expect(TokenType.IDENTIFIER, "expected a variable name but found ${current().describe()}")
            ?: return null
        if (!expectColon("variable '${nameToken.text}'")) return null

        val type = parseType() ?: return null

        var initializer: Expression? = null
        var end = type.span.end
        if (at(TokenType.ASSIGN)) {
            advance()
            skipNestedNewlines()
            initializer = parseExpression() ?: return null
            end = initializer.span.end
        }

        return VariableDeclarationStatement(
            kind = kind,
            kindSpan = kindSpan,
            name = nameToken.text,
            nameSpan = nameToken.span,
            declaredType = type,
            initializer = initializer,
            span = Span(source, start.span.start, end),
        )
    }

    /**
     * The rest of `for (element: type in iterable) body`, after the type has already been read.
     *
     * The declared variable is read-only, so no `val`/`var` is accepted — `for (var e: int in xs)`
     * would claim a mutability the loop does not offer, and silently ignoring the word would be worse
     * than refusing it.
     */
    private fun parseForEachRest(
        keyword: Token,
        nameToken: Token,
        elementType: TypeReference,
    ): Statement? {
        advance() // `in`
        skipNestedNewlines()
        val iterable = parseExpression() ?: run {
            synchronizeToStatement()
            return null
        }
        if (expectRightParen("'for' header") == null) {
            synchronizeToStatement()
            return null
        }
        skipNewlines()
        val body = parseStatement() ?: run {
            synchronizeToStatement()
            return null
        }
        return ForEachStatement(
            keywordSpan = keyword.span,
            name = nameToken.text,
            nameSpan = nameToken.span,
            elementType = elementType,
            iterable = iterable,
            body = body,
            span = Span(source, keyword.span.start, body.span.end),
        )
    }

    /**
     * The rest of the three-part loop, from its initializer to its body.
     *
     * All three of the header's parts are optional, so `for (;;)` parses: Java allows it and it is the
     * idiomatic way to write an endless loop with a `return` inside.
     */
    private fun parseForLoopRest(keyword: Token): Statement? {
        // The header is one logical line: its parts are separated by `;`, not by newlines, so the
        // three parts are read without the newline-sensitivity a statement has.
        val initializer = if (at(TokenType.SEMICOLON)) null else parseForInitializer()
        if (initializer == null && !at(TokenType.SEMICOLON)) {
            synchronizeToStatement()
            return null
        }
        advance() // the `;` after the initializer
        skipNestedNewlines()

        val condition = if (at(TokenType.SEMICOLON)) null else parseExpression()
        if (condition == null && !at(TokenType.SEMICOLON)) {
            synchronizeToStatement()
            return null
        }
        advance() // the `;` after the condition
        skipNestedNewlines()

        val update = if (at(TokenType.RIGHT_PAREN)) null else parseForUpdate()
        if (expectRightParen("'for' header") == null) {
            synchronizeToStatement()
            return null
        }
        skipNewlines()

        val body = parseStatement() ?: run {
            synchronizeToStatement()
            return null
        }
        return ForStatement(
            keywordSpan = keyword.span,
            initializer = initializer,
            condition = condition,
            update = update,
            body = body,
            span = Span(source, keyword.span.start, body.span.end),
        )
    }

    /**
     * The update clause: an increment, a decrement, or an assignment.
     *
     * Only these three, because the update sits between two semicolons and has no block of its own: a
     * `return` or a `try` there would have nowhere to belong. Restricting it here means the analyzer
     * and the emitter see three known statement types instead of any statement at all.
     */
    private fun parseForUpdate(): Statement? {
        val start = current()
        if (at(TokenType.PLUS_PLUS) || at(TokenType.MINUS_MINUS)) {
            val operator = advance()
            val target = advance()
            if (target.type != TokenType.IDENTIFIER) {
                diagnostics.error(
                    "'${operator.text}' applies to a variable, but found ${target.describe()}",
                    target.span,
                )
                return null
            }
            return IncrementStatement(
                operator = operator.text,
                target = NameExpression(target.text, target.span, target.span),
                operatorSpan = operator.span,
                span = Span(source, operator.span.start, target.span.end),
            )
        }

        // A trailing `i++` rather than a leading one: `i++` is the way the loop was written in the
        // request and the way Java writes it, so both spellings are accepted.
        val expression = parseExpression() ?: return null
        if (at(TokenType.PLUS_PLUS) || at(TokenType.MINUS_MINUS)) {
            val operator = advance()
            if (expression !is NameExpression) {
                diagnostics.error(
                    "'${operator.text}' applies to a variable, but found an expression",
                    operator.span,
                )
                return null
            }
            return IncrementStatement(
                operator = operator.text,
                target = expression,
                operatorSpan = operator.span,
                span = Span(source, start.span.start, operator.span.end),
            )
        }

        if (!at(TokenType.ASSIGN)) {
            diagnostics.error(
                "the update part of a 'for' must increment, decrement or assign but found " +
                    current().describe(),
                current().span,
            )
            return null
        }
        advance()
        skipNestedNewlines()
        val value = parseExpression() ?: return null
        if (expression !is NameExpression) {
            diagnostics.error(
                "the update part of a 'for' must assign to a variable",
                expression.span,
            )
            return null
        }
        return AssignmentStatement(
            target = expression,
            value = value,
            span = Span(source, start.span.start, value.span.end),
        )
    }

    /**
     * `when (subject) { 3 -> ...; 2 -> { ... }; else -> ... }`
     *
     * The subject is always parenthesised, and each arm is `value -> statement`; the trailing arm may
     * be `else -> statement`. Newlines separate arms, so a `}` closing an arm body is followed by
     * either another arm or the `}` that closes the `when`.
     */
    private fun parseWhenStatement(): WhenStatement? {
        val keyword = advance()
        val subject = parseParenthesised("when") ?: run {
            synchronizeToStatement()
            return null
        }

        if (!expectLeftBrace("'when' statement")) {
            synchronizeToStatement()
            return null
        }

        val branches = mutableListOf<WhenBranch>()
        var hasElseBranch = false
        skipNewlines()

        while (!at(TokenType.RIGHT_BRACE) && !at(TokenType.END_OF_FILE)) {
            val branchStart = current()
            // A branch separated from the previous one by a comma is tolerated, so that arms may be
            // written on one line.
            if (at(TokenType.COMMA)) {
                advance()
                skipNewlines()
                continue
            }

            var condition: Expression? = null
            if (at(TokenType.ELSE)) {
                advance()
                hasElseBranch = true
            } else {
                condition = parseExpression() ?: run {
                    synchronizeToWhenBranch()
                    skipNewlines()
                    continue
                }
            }

            if (!at(TokenType.ARROW)) {
                error(
                    "expected '->' after the value of a 'when' branch but found ${current().describe()}",
                    current().span,
                )
                synchronizeToWhenBranch()
                skipNewlines()
                continue
            }
            val arrow = advance()

            skipNewlines()
            val body = parseStatement()
            if (body == null) {
                synchronizeToWhenBranch()
                skipNewlines()
                continue
            }

            branches += WhenBranch(
                condition = condition,
                arrowSpan = arrow.span,
                body = body,
                span = Span(source, branchStart.span.start, body.span.end),
            )

            skipNewlines()
        }

        val close = expectRightBrace("'when' statement")
        val end = close?.span?.end ?: subject.span.end
        return WhenStatement(
            subject = subject,
            keywordSpan = keyword.span,
            branches = branches,
            hasElseBranch = hasElseBranch,
            span = Span(source, keyword.span.start, end),
        )
    }

    /**
     * `nya ...` on a line of its own — the same node as the expression form, wrapped as a statement.
     *
     * Nothing distinguishes the two at the syntax level; the difference is only whether the value is
     * used, and that is the analyzer's question rather than the parser's. What the parser does have
     * to decide is whether the `else` that would make the construct worth a value is *demanded*, and
     * here it is not: the failure block on its own is a complete statement.
     */
    private fun parseNyaStatementForm(): Statement? {
        val expression = parseNyaExpression(requireValue = false) ?: return null
        expectStatementEnd("nya statement")
        return ExpressionStatement(expression, expression.span)
    }

    /**
     * Parses the three shapes of `nya`: bare, with a failure block, and with a failure block plus an
     * `else`.
     *
     * The condition is a whole expression rather than a comparison: `nya isReady()` and `nya a == b`
     * are both checks, and the second needs no special reading because `==` is already an operator.
     * That also means a comma here is not a message — there is no second operand. Its *type* is not
     * this layer's business either: with a block the condition is watched for a throw as well as for a
     * false answer, so `nya close() { ... }` parses whatever `close` returns, and the analyzer is what
     * insists on a `bool` for the bare form that has only the throw to describe it.
     *
     * A `{` after the condition is *never* a lambda, which is what lets the three shapes be told
     * apart without brackets around the condition. A lambda is only read where an operand is
     * expected, and by the time the condition has been parsed `parsePostfix` has stopped looking for
     * anything that could continue it — `.`, `(`, `[` and `?` are the whole list, and none of them
     * is a brace. So the brace here belongs to this keyword.
     *
     * [requireValue] is the difference between the two places a `nya` can be written, and it is
     * passed in by the caller rather than guessed at here: in statement position a lone failure block
     * is the whole point (`nya a == b { recover() }`, which then carries on), while in value position
     * something has to say what the value is when the check *passes* — and only an `else` block can.
     * The same node serves both, exactly as the two `try` forms share one.
     */
    private fun parseNyaExpression(requireValue: Boolean): NyaExpression? {
        val keyword = advance()
        if (atStatementEnd()) {
            error("'nya' requires a condition to check", keyword.span)
            return null
        }
        val condition = parseExpression() ?: run {
            synchronizeToStatement()
            return null
        }

        skipNestedNewlines()
        var end = condition.span.end
        var failureBlock: BlockStatement? = null
        var elseBlock: BlockStatement? = null

        if (at(TokenType.LEFT_BRACE)) {
            failureBlock = parseBlock() ?: run {
                synchronizeToStatement()
                return null
            }
            end = failureBlock.span.end

            // As with `if`, the `else` may sit on the same line or the following one.
            val mark = index
            skipNewlines()
            if (at(TokenType.ELSE)) {
                advance()
                skipNewlines()
                val block = if (at(TokenType.LEFT_BRACE)) {
                    parseBlock()
                } else {
                    error(
                        "expected '{' to start the 'else' block but found ${current().describe()}",
                        current().span,
                    )
                    null
                }
                if (block == null) {
                    synchronizeToStatement()
                    return null
                }
                elseBlock = block
                end = block.span.end
            } else {
                index = mark
            }
        }

        if (requireValue) {
            // A `nya` in value position owes a value on *both* readings. The bare form throws instead
            // of producing one, and the lone failure block covers only the reading where the
            // condition was false, so neither can be the source of the value that was asked for.
            if (failureBlock == null) {
                error(
                    "this 'nya' is used as a value, so it needs both blocks: write " +
                        "'nya condition { yield ... } else { yield ... }' — the failure block gives " +
                        "the value when the condition is false, and the 'else' block gives it when " +
                        "the condition holds",
                    keyword.span,
                )
            } else if (elseBlock == null) {
                error(
                    "this 'nya' is used as a value but has only a failure block: add " +
                        "'else { yield ... }' to say what the value is when the condition holds",
                    keyword.span,
                )
            }
        }

        return NyaExpression(
            keywordSpan = keyword.span,
            condition = condition,
            failureBlock = failureBlock,
            elseBlock = elseBlock,
            span = Span(source, keyword.span.start, end),
        )
    }

    /**
     * `scratch e1, e2`, or a bare `scratch` for a handler whose `emit` clause names no values.
     *
     * An expression rather than a statement: `v: int = scratch 1, 2` keeps what the handler answers.
     * The caller's `parseAssignmentOrExpression` path reaches it through the expression table, so this
     * is only the operand shape — the values follow, comma-separated.
     */
    private fun parseScratchExpression(): ScratchExpression? {
        val keyword = advance()
        val values = mutableListOf<Expression>()
        var end = keyword.span.end

        if (!atStatementEnd() && !at(TokenType.COMMA)) {
            while (true) {
                val value = parseExpression() ?: run {
                    synchronizeToStatement()
                    return null
                }
                values += value
                end = value.span.end
                if (at(TokenType.COMMA)) {
                    advance()
                    skipNestedNewlines()
                    continue
                }
                break
            }
        }

        return ScratchExpression(
            keywordSpan = keyword.span,
            values = values,
            span = Span(source, keyword.span.start, end),
        )
    }

    /** `nyan value` — leaves the enclosing `try` handler, answering the value. */
    /**
     * `nyan` on its own, or `nyan value`.
     *
     * The value is optional: a `nyan` answers whatever the `scratch` sites reaching this handler asked
     * for, and when they ask for nothing — every one of them discarding the answer — a bare `nyan` is
     * the plain `return` of a `void` method. The analyzer checks that the two agree, so a bare `nyan`
     * where a value is wanted is reported there rather than guessed at here.
     */
    private fun parseNyanStatement(): NyanStatement? {
        val keyword = advance()
        if (atStatementEnd()) {
            return NyanStatement(keyword.span, value = null, span = keyword.span)
        }

        val value = parseExpression() ?: run {
            synchronizeToStatement()
            return null
        }
        expectStatementEnd("nyan statement")
        return NyanStatement(
            keywordSpan = keyword.span,
            value = value,
            span = Span(source, keyword.span.start, value.span.end),
        )
    }

    /**
     * `purr { ... }`, `purred { ... }`, `purrcls { ... }` and `purredcls { ... }` — the four locking
     * forms, one keyword each.
     *
     * The keyword alone says which lock is held and in which mode, so there is nothing to read before
     * the brace: `purr` is the instance lock written, `purred` the same lock read, and the `cls`
     * spellings take the class lock instead. Because each form is one word, `purr key { }` needs no
     * special handling — `key` is simply not where a `{` was expected, and the error says so.
     */
    private fun parsePurrStatement(): Statement? {
        val keyword = advance()
        val lock = when (keyword.type) {
            TokenType.PURRCLS, TokenType.PURREDCLS -> PurrLock.CLASS
            else -> PurrLock.INSTANCE
        }
        val mode = when (keyword.type) {
            TokenType.PURRED, TokenType.PURREDCLS -> PurrMode.READ
            else -> PurrMode.WRITE
        }

        if (!at(TokenType.LEFT_BRACE)) {
            error(
                "expected '{' to start the '${keyword.text}' block but found ${current().describe()}",
                current().span,
            )
            synchronizeToStatement()
            return null
        }

        val body = parseBlock() ?: run {
            synchronizeToStatement()
            return null
        }
        return PurrStatement(
            keywordSpan = keyword.span,
            keyword = keyword.text,
            lock = lock,
            mode = mode,
            body = body,
            span = Span(source, keyword.span.start, body.span.end),
        )
    }

    /**
     * `try call(args) { a, b -> ... }` — the one remaining `try`, in statement position.
     *
     * The other form this keyword used to introduce, `try { ... } catch (e: T) { ... }`, is gone:
     * `nya condition { ... }` handles a broken belief and lets the program carry on, and a JVM
     * exception that nothing catches already propagates on its own, so a block plus a handler for it
     * was a second way to say what the language no longer needs said. A `{` after the keyword is
     * therefore only ever a mistake, and it is reported as one rather than parsed.
     */
    private fun parseTryStatement(): Statement? {
        val keyword = advance()
        val mark = index
        skipNewlines()

        if (at(TokenType.LEFT_BRACE)) {
            error(
                "a 'try' block no longer exists: 'try' only declares a handler for a call, " +
                    "written 'try call(...) { values -> ... }', and an exception nothing catches " +
                    "already propagates",
                keyword.span,
            )
            synchronizeToStatement()
            return null
        }

        // Parsing is restarted from just after the keyword so that a malformed call is reported
        // against the call itself rather than against the newline that follows it.
        index = mark
        return when (val form = parseTryForm(keyword)) {
            null -> null
            is TryForm.Handler -> TryStatement(
                keywordSpan = keyword.span,
                call = form.parts.call,
                extractorParameters = form.parts.parameters,
                extractorBody = form.parts.body,
                arrowSpan = form.parts.arrowSpan,
                span = form.parts.span,
            )
            is TryForm.Scratch -> TryScratchStatement(
                keywordSpan = keyword.span,
                call = form.call,
                scratch = form.scratch,
                span = form.span,
            )
        }
    }

    /**
     * `try call(args) { a, b -> ... }` — wraps a call in a handler that its `scratch` sites reach.
     *
     * The handler is written like a lambda parameter list, so the same `{ a, b -> ... }` shape is
     * reused; there it is parsed as an expression, here as the second half of the statement.
     */
    private fun parseExtractorTry(keyword: Token): TryStatement? {
        val parts = parseExtractorTryParts(keyword) ?: return null
        return TryStatement(
            keywordSpan = keyword.span,
            call = parts.call,
            extractorParameters = parts.parameters,
            extractorBody = parts.body,
            arrowSpan = parts.arrowSpan,
            span = parts.span,
        )
    }

    /**
     * What follows `try call(...)`: a handler block, or a `scratch` naming one declared elsewhere.
     *
     * The two are alternatives rather than variants of one shape, because they answer different
     * questions — "run this when the call scratches" versus "send the call's *failure* to that
     * handler" — so the parser decides between them on the token after the call and each position
     * builds its own node from the result.
     */
    private sealed class TryForm {
        /** `try call(...) { a, b -> ... }` — declares a handler. */
        class Handler(val parts: ExtractorTryParts) : TryForm()

        /** `try call(...) scratch v1, v2` — catches a Java exception and enters an existing handler. */
        class Scratch(val call: CallExpression, val scratch: ScratchExpression, val span: Span) : TryForm()
    }

    /**
     * Parses `try call(...)` and whatever tail followed it.
     *
     * The call is parsed once for both forms so that a malformed callee is reported against the call
     * itself, in the same words, wherever the construct was written.
     */
    private fun parseTryForm(keyword: Token): TryForm? {
        val call = parseExpression() as? CallExpression ?: run {
            error(
                "'try' must be followed by a call: either 'try call(...) { values -> ... }' to declare " +
                    "a handler, or 'try call(...) scratch values' to send a thrown exception to one",
                current().span,
            )
            synchronizeToStatement()
            return null
        }

        skipNewlines()
        // `scratch` names the handler to enter when the call throws, so it replaces the block rather
        // than following it. No ambiguity with a `scratch` statement written on the next line: a
        // parenthesised `try call(...)` with no block was never valid, so nothing that parses today
        // changes meaning here.
        if (at(TokenType.SCRATCH)) {
            val scratch = parseScratchExpression() ?: run {
                synchronizeToStatement()
                return null
            }
            return TryForm.Scratch(call, scratch, Span(source, keyword.span.start, scratch.span.end))
        }

        val parts = parseExtractorTryBody(keyword, call) ?: return null
        return TryForm.Handler(parts)
    }

    /**
     * `try call(args) { a, b -> ... }` in expression position, as in
     * `result: int = try emitTest(true) { a, b -> yield 1 }`.
     *
     * The shape is exactly the statement's; only what the construct is worth differs, which the
     * analyzer decides from the `yield` the extractor body may write.
     */
    private fun parseExtractorTryExpression(keyword: Token): Expression? {
        return when (val form = parseTryForm(keyword)) {
            null -> null
            is TryForm.Handler -> TryExpression(
                keywordSpan = keyword.span,
                call = form.parts.call,
                extractorParameters = form.parts.parameters,
                extractorBody = form.parts.body,
                arrowSpan = form.parts.arrowSpan,
                span = form.parts.span,
            )
            is TryForm.Scratch -> TryScratchExpression(
                keywordSpan = keyword.span,
                call = form.call,
                scratch = form.scratch,
                span = form.span,
            )
        }
    }

    /** The pieces both forms of `try call(...) { a -> ... }` are built from. */
    private class ExtractorTryParts(
        val call: CallExpression,
        val parameters: List<LambdaParameter>,
        val body: BlockStatement,
        val arrowSpan: Span?,
        val span: Span,
    )

    /**
     * Parses `call(args) { a, b -> ... }` after the `try` keyword has been consumed.
     *
     * Kept for the expression position, which has no `scratch` form of its own to choose between —
     * it parses the call and then requires the block.
     */
    private fun parseExtractorTryParts(keyword: Token): ExtractorTryParts? {
        val call = parseExpression() as? CallExpression ?: run {
            error(
                "'try' must be followed by a block, or by a call to a function with an 'emit' clause",
                current().span,
            )
            synchronizeToStatement()
            return null
        }

        skipNewlines()
        return parseExtractorTryBody(keyword, call)
    }

    /**
     * Parses the `{ a, b -> ... }` half, given the call that precedes it.
     *
     * Split out from [parseExtractorTryParts] so the statement position can choose between this and
     * the `scratch` form after reading the call once.
     */
    private fun parseExtractorTryBody(keyword: Token, call: CallExpression): ExtractorTryParts? {
        if (!expectLeftBrace("'try' extractor")) {
            synchronizeToStatement()
            return null
        }

        val parameters = mutableListOf<LambdaParameter>()
        var arrowSpan: Span? = null

        // The handler may name fewer values than the callee's clause declares, and may name none at
        // all: `try f(1) { -> ... }`. Parse the parameter list only when an arrow follows it.
        if (!at(TokenType.ARROW)) {
            if (!at(TokenType.RIGHT_BRACE)) {
                parseExtractorParameters(parameters)
            }
            skipNewlines()
            if (at(TokenType.ARROW)) {
                arrowSpan = advance().span
            } else {
                error(
                    "expected '->' after the extractor parameters of a 'try' but found " +
                        current().describe(),
                    current().span,
                )
            }
        } else {
            arrowSpan = advance().span
        }

        skipNewlines()
        val statements = parseStatementSequence()
        val close = expectRightBrace("'try' extractor")
        val end = close?.span?.end ?: call.span.end
        val body = BlockStatement(statements, Span(source, keyword.span.start, end))

        return ExtractorTryParts(
            call = call,
            parameters = parameters,
            body = body,
            arrowSpan = arrowSpan,
            span = Span(source, keyword.span.start, end),
        )
    }

    /**
     * Parses `a, b` — the parameter names of a `try` extractor.
     *
     * A type annotation is accepted and ignored, so `{ a: int, b: int -> ... }` also parses; the
     * analyzer takes the real types from the callee's `emit` clause.
     */
    private fun parseExtractorParameters(into: MutableList<LambdaParameter>) {
        while (true) {
            if (at(TokenType.RIGHT_BRACE) || at(TokenType.ARROW)) return
            val nameToken = expect(
                TokenType.IDENTIFIER,
                "expected an extractor parameter name but found ${current().describe()}",
            ) ?: return

            var declaredType: TypeReference? = null
            if (at(TokenType.COLON)) {
                advance()
                declaredType = parseType()
            }

            into += LambdaParameter(
                name = nameToken.text,
                nameSpan = nameToken.span,
                declaredType = declaredType,
                span = Span(source, nameToken.span.start, declaredType?.span?.end ?: nameToken.span.end),
            )

            if (at(TokenType.COMMA)) {
                advance()
                skipNestedNewlines()
                continue
            }
            return
        }
    }

    /** Parses `( expression )`, reporting a precise error when the shape is wrong. */
    private fun parseParenthesised(owner: String): Expression? {
        if (!expectLeftParen(owner)) return null
        newlineInsensitiveDepth++
        skipNestedNewlines()
        val condition = parseExpression()
        skipNestedNewlines()
        expectRightParen(owner)
        newlineInsensitiveDepth--
        return condition
    }

    // ------------------------------------------------------------- expressions

    /** Entry point of the precedence-climbing expression parser. */
    private fun parseExpression(): Expression? = parseBinary(0)

    /** Binding power of a binary operator; negative when [type] is not a binary operator. */
    private fun precedenceOf(type: TokenType): Int = when (type) {
        TokenType.OR_OR -> 1
        TokenType.AND_AND -> 2
        TokenType.EQUALS_EQUALS, TokenType.BANG_EQUALS -> 3
        TokenType.LESS, TokenType.LESS_EQUALS, TokenType.GREATER, TokenType.GREATER_EQUALS -> 4
        TokenType.PLUS, TokenType.MINUS -> 5
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 6
        else -> -1
    }

    private fun binaryOperatorOf(type: TokenType): BinaryOperator? = when (type) {
        TokenType.PLUS -> BinaryOperator.ADD
        TokenType.MINUS -> BinaryOperator.SUBTRACT
        TokenType.STAR -> BinaryOperator.MULTIPLY
        TokenType.SLASH -> BinaryOperator.DIVIDE
        TokenType.PERCENT -> BinaryOperator.REMAINDER
        TokenType.EQUALS_EQUALS -> BinaryOperator.EQUAL
        TokenType.BANG_EQUALS -> BinaryOperator.NOT_EQUAL
        TokenType.LESS -> BinaryOperator.LESS
        TokenType.LESS_EQUALS -> BinaryOperator.LESS_EQUAL
        TokenType.GREATER -> BinaryOperator.GREATER
        TokenType.GREATER_EQUALS -> BinaryOperator.GREATER_EQUAL
        TokenType.AND_AND -> BinaryOperator.AND
        TokenType.OR_OR -> BinaryOperator.OR
        else -> null
    }

    private fun parseBinary(minimumPrecedence: Int): Expression? {
        var left = parseUnary() ?: return null

        while (true) {
            skipNestedNewlines()
            val precedence = precedenceOf(current().type)
            if (precedence < 0 || precedence < minimumPrecedence) return left

            val operatorToken = advance()
            skipNestedNewlines()
            val right = parseBinary(precedence + 1) ?: return left
            val operator = binaryOperatorOf(operatorToken.type)!!

            // `a > b > c` is a *chain*, meaning `a > b && b > c`; read left-associatively it would
            // compare a bool with an int. A lone comparison still builds an ordinary
            // BinaryExpression, and a second relational operator promotes it to a chain.
            if (isChainable(operatorToken.type)) {
                left = when (val previous = left) {
                    is ChainedComparisonExpression -> ChainedComparisonExpression(
                        operands = previous.operands + right,
                        operators = previous.operators + operator,
                        operatorSpans = previous.operatorSpans + operatorToken.span,
                        span = Span(source, previous.span.start, right.span.end),
                    )

                    is BinaryExpression ->
                        if (isChainable(previous.operator)) {
                            ChainedComparisonExpression(
                                operands = listOf(previous.left, previous.right, right),
                                operators = listOf(previous.operator, operator),
                                operatorSpans = listOf(previous.operatorSpan, operatorToken.span),
                                span = Span(source, previous.span.start, right.span.end),
                            )
                        } else {
                            BinaryExpression(
                                left = previous,
                                operatorSpan = operatorToken.span,
                                operator = operator,
                                right = right,
                                span = Span(source, previous.span.start, right.span.end),
                            )
                        }

                    else -> BinaryExpression(
                        left = previous,
                        operatorSpan = operatorToken.span,
                        operator = operator,
                        right = right,
                        span = Span(source, previous.span.start, right.span.end),
                    )
                }
                continue
            }

            left = BinaryExpression(
                left = left,
                operatorSpan = operatorToken.span,
                operator = operator,
                right = right,
                span = Span(source, left.span.start, right.span.end),
            )
        }
    }

    /** Whether [type] is a relational operator, i.e. one that may appear in a comparison chain. */
    private fun isChainable(type: TokenType): Boolean = when (type) {
        TokenType.LESS, TokenType.LESS_EQUALS, TokenType.GREATER, TokenType.GREATER_EQUALS -> true
        else -> false
    }

    /** Whether [operator] is relational, mirroring [isChainable] for an already-parsed operator. */
    private fun isChainable(operator: BinaryOperator): Boolean = when (operator) {
        BinaryOperator.LESS, BinaryOperator.LESS_EQUAL,
        BinaryOperator.GREATER, BinaryOperator.GREATER_EQUAL,
        -> true

        else -> false
    }

    private fun parseUnary(): Expression? {
        val token = current()
        if (token.type == TokenType.MINUS || token.type == TokenType.BANG) {
            advance()
            val operand = parseUnary() ?: return null
            val operator = if (token.type == TokenType.MINUS) UnaryOperator.NEGATE else UnaryOperator.NOT
            return UnaryExpression(
                operatorSpan = token.span,
                operator = operator,
                operand = operand,
                span = Span(source, token.span.start, operand.span.end),
            )
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expression? {
        var expression = parsePrimary() ?: return null

        while (true) {
            skipNestedNewlines()
            when {
                // `callee?(arguments)` — a call that is skipped when the function value is absent.
                // The `?` belongs to the *call*, not to the callee: `f?(x)` calls the value `f` only
                // if it is there, so the flag is recorded on the call the `(` is about to build.
                at(TokenType.QUESTION) && peek(1).type == TokenType.LEFT_PAREN -> {
                    advance() // `?`
                    expression = parseArgumentList(expression, safeCall = true)
                }

                at(TokenType.LEFT_PAREN) -> {
                    expression = parseArgumentList(expression, safeCall = false)
                }

                at(TokenType.DOT) -> {
                    advance()
                    val member = expect(
                        TokenType.IDENTIFIER,
                        "expected a member name after '.' but found ${current().describe()}",
                    ) ?: return expression
                    expression = MemberAccessExpression(
                        receiver = expression,
                        memberName = member.text,
                        memberSpan = member.span,
                        span = Span(source, expression.span.start, member.span.end),
                    )
                }

                // `receiver?.member` — the safe call, which reads the member only when the receiver
                // is not null. The `?.` is two tokens, and the `?` is only a safe call when a `.`
                // follows it: anywhere else a `?` is the type suffix, which never reaches an
                // expression context.
                at(TokenType.QUESTION) && peek(1).type == TokenType.DOT -> {
                    advance() // `?`
                    advance() // `.`
                    val member = expect(
                        TokenType.IDENTIFIER,
                        "expected a member name after '?.' but found ${current().describe()}",
                    ) ?: return expression
                    expression = MemberAccessExpression(
                        receiver = expression,
                        memberName = member.text,
                        memberSpan = member.span,
                        span = Span(source, expression.span.start, member.span.end),
                        isSafeCall = true,
                    )
                }

                at(TokenType.LEFT_BRACKET) -> {
                    val open = advance()
                    newlineInsensitiveDepth++
                    skipNestedNewlines()
                    val index = parseExpression() ?: run {
                        newlineInsensitiveDepth--
                        synchronizeToStatement()
                        return expression
                    }
                    skipNestedNewlines()
                    val close = expectRightBracket("array index")
                    newlineInsensitiveDepth--
                    val end = close?.span?.end ?: index.span.end
                    expression = IndexExpression(
                        array = expression,
                        index = index,
                        indexSpan = Span(source, open.span.start, end),
                        span = Span(source, expression.span.start, end),
                    )
                }

                else -> return expression
            }
        }
    }

    /**
     * Parses `(arguments)` and builds the call it belongs to.
     *
     * [safeCall] is what `callee?(...)` sets: the call is skipped when the function value being called
     * is absent. The two spellings share everything but that flag, so they share this method rather
     * than duplicating an argument list that has to stay in step with itself.
     */
    private fun parseArgumentList(callee: Expression, safeCall: Boolean): Expression {
        val open = advance() // `(`
        newlineInsensitiveDepth++
        skipNestedNewlines()
        val arguments = mutableListOf<Expression>()
        if (!at(TokenType.RIGHT_PAREN)) {
            while (true) {
                val argument = parseExpression() ?: break
                arguments += argument
                skipNestedNewlines()
                if (at(TokenType.COMMA)) {
                    advance()
                    skipNestedNewlines()
                    continue
                }
                break
            }
        }
        skipNestedNewlines()
        val close = expectRightParen("argument list")
        newlineInsensitiveDepth--
        val end = close?.span?.end ?: current().span.end
        return CallExpression(
            callee = callee,
            arguments = arguments,
            argumentListSpan = Span(source, open.span.start, end),
            span = Span(source, callee.span.start, end),
            isSafeCall = safeCall,
        )
    }

    private fun parsePrimary(): Expression? {
        val token = current()
        return when (token.type) {
            TokenType.INTEGER_LITERAL -> {
                advance()
                // The suffix is part of the token's text: it is what distinguishes "a whole number"
                // from "a whole number that is a long", and the analyzer needs to know which was
                // written so it can refuse to narrow `1L` into an `int`.
                IntegerLiteral(
                    value = token.literalValue,
                    isLong = token.text.endsWith("L") || token.text.endsWith("l"),
                    span = token.span,
                )
            }

            TokenType.FLOAT_LITERAL -> {
                advance()
                DoubleLiteral(token.floatValue, token.isFloatSuffixed, token.span)
            }

            TokenType.CHAR_LITERAL -> {
                advance()
                // The lexer stores a character's value as a number, exactly as it stores a whole
                // number, so the two cannot drift apart; the parser is where it becomes a character
                // again. A literal the lexer rejected still carries a value, so this cannot throw.
                CharLiteral(token.literalValue.toInt().toChar(), token.span)
            }

            TokenType.STRING_LITERAL -> {
                advance()
                StringLiteral(token.stringValue, token.span)
            }

            TokenType.TRUE, TokenType.FALSE -> {
                advance()
                BoolLiteral(token.type == TokenType.TRUE, token.span)
            }

            TokenType.NULL -> {
                advance()
                NullLiteral(token.span)
            }

            TokenType.IDENTIFIER -> {
                advance()
                NameExpression(token.text, token.span, token.span)
            }

            TokenType.THIS -> {
                advance()
                ThisExpression(token.span, token.span)
            }

            TokenType.LEFT_PAREN -> {
                advance()
                newlineInsensitiveDepth++
                skipNestedNewlines()
                val inner = parseExpression()
                skipNestedNewlines()
                expectRightParen("parenthesised expression")
                newlineInsensitiveDepth--
                inner
            }

            TokenType.LEFT_BRACE -> parseLambdaExpression()

            // `v: int = scratch 1, 2` — an expression, because the handler it enters answers a value.
            TokenType.SCRATCH -> parseScratchExpression()

            // `result: int = nya a == b { yield 2 } else { yield 1 }` asks for a value, so the parser
            // demands both blocks here and refuses the two forms that would leave one answer missing.
            TokenType.NYA -> parseNyaExpression(requireValue = true)

            // `try f(...) { a -> yield 1 }` is an expression when what it is worth is used, as in
            // `result: int = try f(...) { a -> yield 1 }`. The brace form that caught a thrown
            // exception is gone and is reported as such rather than silently accepted.
            TokenType.TRY -> {
                val keyword = advance()
                skipNestedNewlines()
                if (at(TokenType.LEFT_BRACE)) {
                    error(
                        "a 'try' block no longer exists: 'try' is worth a value only with an " +
                            "extractor — write 'try call(...) { values -> yield ... }'",
                        keyword.span,
                    )
                    null
                } else {
                    parseExtractorTryExpression(keyword)
                }
            }

            else -> {
                error("expected an expression but found ${token.describe()}", token.span)
                null
            }
        }
    }

    /**
     * `{ x, y -> yield x + y }`
     *
     * In expression position a `{` always begins a lambda, because blocks are never expressions.
     */
    private fun parseLambdaExpression(): LambdaExpression? {
        val open = advance() // `{`
        val outerDepth = newlineInsensitiveDepth

        // A lambda's parameter list may wrap across lines.
        newlineInsensitiveDepth++
        val parameters = mutableListOf<LambdaParameter>()
        // Whether this `{` opens a lambda *with* a parameter list, which is decided by one question:
        // is there an `->` before the matching `}` at this depth? A body written `{ println(1) }` has
        // no arrow, so the brace is not a parameter list at all — it is a block whose contents are
        // statements, which is the form `run({ ... })` needs. Reading `println` as a parameter name
        // and then demanding an arrow is what used to make that form impossible to write.
        val hasArrow = arrowBeforeMatchingBrace()
        if (hasArrow && !at(TokenType.ARROW)) {
            while (true) {
                skipNestedNewlines()
                if (at(TokenType.ARROW)) break

                val nameToken = expect(
                    TokenType.IDENTIFIER,
                    "expected a lambda parameter name but found ${current().describe()}",
                ) ?: break

                var declaredType: TypeReference? = null
                if (at(TokenType.COLON)) {
                    advance()
                    declaredType = parseType()
                }

                parameters += LambdaParameter(
                    name = nameToken.text,
                    nameSpan = nameToken.span,
                    declaredType = declaredType,
                    span = Span(source, nameToken.span.start, declaredType?.span?.end ?: nameToken.span.end),
                )

                skipNestedNewlines()
                if (at(TokenType.COMMA)) {
                    advance()
                    continue
                }
                break
            }
        }

        skipNestedNewlines()
        var arrowFound = false
        if (at(TokenType.ARROW)) {
            advance()
            arrowFound = true
        }
        newlineInsensitiveDepth = outerDepth

        // `{ }` and `{ println(1) }` — a lambda with no parameters, written without the arrow it has
        // nothing to introduce. The brace is then a plain statement block, exactly as it would be
        // after `if` or `while`.
        if (!arrowFound) {
            val statements = parseStatementSequence()
            val close = expectRightBrace("lambda body")
            val end = close?.span?.end ?: current().span.end
            return LambdaExpression(
                parameters = emptyList(),
                body = BlockStatement(statements, Span(source, open.span.start, end)),
                span = Span(source, open.span.start, end),
            )
        }

        // The body is an ordinary statement block: newlines terminate its statements.
        val statements = parseStatementSequence()
        val close = expectRightBrace("lambda body")
        val end = close?.span?.end ?: current().span.end
        val body = BlockStatement(statements, Span(source, open.span.start, end))

        return LambdaExpression(
            parameters = parameters,
            body = body,
            span = Span(source, open.span.start, end),
        )
    }

    /**
     * Whether an `->` appears between here and the `}` that closes the brace just consumed.
     *
     * This is what tells a lambda's *parameter list* from a lambda's *body*. Both are `{ … }` and the
     * parser cannot know which it is looking at without looking ahead: reading an identifier as a
     * parameter name is only correct when an arrow follows it, and demanding an arrow otherwise made
     * `run({ println(1) })` unparseable — the shortest and most natural way to write a no-argument
     * lambda.
     *
     * Only starts at this brace's own depth count. A nested brace resets the question for its own
     * contents, so `{ x -> { y -> x + y } }` still sees the outer arrow, while the inner lambda's
     * arrow is not mistaken for the outer one. A `)` or `]` at the outer depth ends the scan too,
     * which stops `f({ }, other -> …)` from borrowing the neighbouring lambda's arrow.
     *
     * The scan gives up at the end of the token list, so a malformed file reports its real error from
     * the ordinary parse path rather than looping here.
     */
    private fun arrowBeforeMatchingBrace(): Boolean {
        var depth = 0
        var offset = 0
        while (true) {
            val token = peek(offset)
            when (token.type) {
                TokenType.END_OF_FILE -> return false
                TokenType.LEFT_BRACE, TokenType.LEFT_PAREN, TokenType.LEFT_BRACKET -> depth++
                TokenType.RIGHT_BRACE -> {
                    if (depth == 0) return false
                    depth--
                }
                TokenType.RIGHT_PAREN, TokenType.RIGHT_BRACKET -> {
                    if (depth == 0) return false
                    depth--
                }
                // The arrow that belongs to *this* brace, before any nested one opens.
                TokenType.ARROW -> if (depth == 0) return true
                else -> Unit
            }
            offset++
        }
    }

    // -------------------------------------------------------------------- types

    /**
     * `<string>`, `<T1, T2>` — the type arguments written after a type's name, or an empty list.
     *
     * The list is empty when no `<` is present. Nested brackets are read by recursion, so
     * `Iterable<Iterator<int>>` closes at the right `>` rather than at the first one.
     */
    private fun parseTypeArguments(): List<TypeReference> {
        if (!at(TokenType.LESS)) return emptyList()
        advance()

        val arguments = mutableListOf<TypeReference>()
        // A newline inside `<...>` is not a statement boundary; the delimiters already say where the
        // list ends, so long argument lists may be wrapped.
        skipNestedNewlines()
        while (!at(TokenType.GREATER) && !at(TokenType.END_OF_FILE)) {
            val argument = parseType() ?: return arguments
            arguments += argument
            skipNestedNewlines()
            if (at(TokenType.COMMA)) {
                advance()
                skipNestedNewlines()
            } else {
                break
            }
        }
        if (!at(TokenType.GREATER)) {
            error(
                "expected '>' to close the type arguments but found ${current().describe()}",
                current().span,
            )
            return arguments
        }
        advance()
        return arguments
    }

    /**
     * Parses a type: `int`, `string[]`, `[int, int] -> int`, `java.util.HashMap`.
     *
     * Returns `null` (after reporting) when no valid type is present.
     */
    private fun parseType(): TypeReference? {
        val start = current()
        var result: TypeReference = when (start.type) {
            TokenType.LEFT_BRACKET -> parseFunctionTypeFromBrackets() ?: return null
            TokenType.IDENTIFIER -> {
                advance()
                // A qualified name: `java.util.HashMap`. The whole dotted name is one identifier as
                // far as the type system is concerned — it names a class, not a member of one — so the
                // segments are joined back into a single name rather than being modelled as a path.
                // A type position has no other meaning for `.`, which is why this cannot shadow a
                // member access: there is no value here to reach into.
                val name = StringBuilder(start.text)
                var last = start
                while (at(TokenType.DOT) && isNameSegment(peek(1))) {
                    advance() // `.`
                    val segment = advance()
                    name.append('.').append(segment.text)
                    last = segment
                }
                // `<...>` on a type: captured rather than skipped, because `for ... in` needs the
                // argument to know what an iterable yields. The JVM still erases it.
                val arguments = parseTypeArguments()
                val end = arguments.lastOrNull()?.span?.end ?: last.span.end
                NamedTypeReference(
                    name.toString(),
                    Span(source, start.span.start, last.span.end),
                    arguments,
                    Span(source, start.span.start, end),
                )
            }

            // `([int] -> int)?` — parentheses, so that a `?` can apply to the function type as a
            // whole. Without them there is no way to say "a function that may be absent": a function
            // type ends in its return type, so `[int] -> int?` already means "returns an `int?`" and
            // the `?` has to attach to the return type.
            TokenType.LEFT_PAREN -> parseParenthesizedType() ?: return null

            else -> {
                error("expected a type but found ${start.describe()}", start.span)
                return null
            }
        }

        // Trailing `[]` and `?` suffixes, e.g. `string[]`, `string?`, `string?[]`.
        //
        // Either may be written, in either order, because they answer different questions: `string?[]`
        // is an array of strings that may be absent, and `string[]?` is an array that may itself be
        // absent. `?` therefore applies to whatever has been built so far.
        while (true) {
            when {
                at(TokenType.LEFT_BRACKET) && peek(1).type == TokenType.RIGHT_BRACKET -> {
                    advance()
                    val close = advance()
                    result = ArrayTypeReference(result, Span(source, result.span.start, close.span.end))
                }

                at(TokenType.QUESTION) -> {
                    val question = advance()
                    if (result is NullableTypeReference) {
                        // `string??` is not a stronger promise than `string?`: nullability is a
                        // property a type either has or lacks, so the second `?` says nothing.
                        error(
                            "a type may be nullable only once: write '${result.span.text}' and drop " +
                                "the extra '?'",
                            question.span,
                        )
                    } else {
                        result = NullableTypeReference(
                            inner = result,
                            questionSpan = question.span,
                            span = Span(source, result.span.start, question.span.end),
                        )
                    }
                }

                else -> return result
            }
        }
    }

    /**
     * Parses `(Type)` and returns the inner type, spans widened to include the parentheses.
     *
     * The parentheses are not a type of their own — they only group, so that a trailing `?` or `[]`
     * can apply to what is inside them rather than to the last part of it. That is what makes
     * `([int] -> int)?` say "a function value that may be absent" where `[int] -> int?` says "a
     * function returning an absent int".
     *
     * The result is the inner reference with a *widened span*, and that is what the error messages
     * quote back: reporting a `([int] -> int)?` as `(int -> int` or as a bare `int` would describe a
     * type the reader did not write.
     */
    private fun parseParenthesizedType(): TypeReference? {
        val open = advance() // `(`
        skipNestedNewlines()
        val inner = parseType() ?: return null
        skipNestedNewlines()
        val close = expectRightParen("type") ?: return null
        val span = Span(source, open.span.start, close.span.end)
        return withSpan(inner, span)
    }

    /** [reference] with its span replaced by [span], keeping its structure. */
    private fun withSpan(reference: TypeReference, span: Span): TypeReference = when (reference) {
        is NamedTypeReference ->
            NamedTypeReference(reference.name, reference.nameSpan, reference.arguments, span)
        is ArrayTypeReference -> ArrayTypeReference(reference.element, span)
        is FunctionTypeReference -> FunctionTypeReference(reference.parameters, reference.returnType, span)
        is NullableTypeReference -> NullableTypeReference(reference.inner, reference.questionSpan, span)
    }

    /** Parses the `[int, int]` part of a function type together with its mandatory `-> return`. */
    private fun parseFunctionTypeFromBrackets(): TypeReference? {
        val open = advance() // `[`
        val parameters = mutableListOf<TypeReference>()

        if (!at(TokenType.RIGHT_BRACKET)) {
            while (true) {
                val parameter = parseType() ?: break
                parameters += parameter
                if (at(TokenType.COMMA)) {
                    advance()
                    continue
                }
                break
            }
        }

        expect(
            TokenType.RIGHT_BRACKET,
            "expected ']' to close the parameter types of a function type but found ${current().describe()}",
        ) ?: return null

        if (!at(TokenType.ARROW)) {
            error(
                "expected '->' after the parameter types of a function type but found ${current().describe()}; " +
                    "write the function type as '[" + parameters.joinToString(", ") { "T" } + "] -> R'",
                current().span,
            )
            return null
        }
        advance()

        val returnType = parseType() ?: return null
        return FunctionTypeReference(
            parameters = parameters,
            returnType = returnType,
            span = Span(source, open.span.start, returnType.span.end),
        )
    }

    // ---------------------------------------------------------------- plumbing

    private fun current(): Token = tokens[index]

    private fun peek(offset: Int): Token = tokens[(index + offset).coerceIn(0, tokens.size - 1)]

    private fun advance(): Token {
        val token = tokens[index]
        if (index < tokens.size - 1) index++
        return token
    }

    private fun at(type: TokenType): Boolean = current().type == type

    /** `true` at a newline, a `}`, a `;`, or end of file. */
    private fun atStatementEnd(): Boolean =
        at(TokenType.NEWLINE) || at(TokenType.RIGHT_BRACE) || at(TokenType.SEMICOLON) || at(TokenType.END_OF_FILE)

    /**
     * Skips newlines only when inside a `(` or `[` list, so wrapped argument lists parse while
     * statement separation elsewhere is preserved.
     */
    private fun skipNestedNewlines() {
        if (newlineInsensitiveDepth == 0) return
        while (at(TokenType.NEWLINE)) advance()
    }

    /** Skips newlines unconditionally; used between statements and members. */
    private fun skipNewlines() {
        while (at(TokenType.NEWLINE)) advance()
    }

    /** Consumes the statement separator, unless the statement already ends at `}` or end of file. */
    private fun expectStatementEnd(owner: String) {
        when {
            at(TokenType.NEWLINE) -> advance()
            at(TokenType.SEMICOLON) -> {
                advance()
                skipNewlines()
            }

            at(TokenType.RIGHT_BRACE) || at(TokenType.END_OF_FILE) -> Unit

            else -> {
                error(
                    "expected a newline to end the $owner but found ${current().describe()}; " +
                        "each statement must start on its own line",
                    current().span,
                )
                synchronizeToStatement()
            }
        }
    }

    private fun expect(type: TokenType, message: String): Token? {
        if (at(type)) return advance()
        error(message, current().span)
        return null
    }

    private fun expectColon(owner: String): Boolean {
        if (at(TokenType.COLON)) {
            advance()
            return true
        }
        error(
            "expected ':' after $owner to introduce its type but found ${current().describe()}",
            current().span,
        )
        return false
    }

    private fun expectLeftBrace(owner: String): Boolean {
        if (at(TokenType.LEFT_BRACE)) {
            advance()
            return true
        }
        error("expected '{' to open the body of $owner but found ${current().describe()}", current().span)
        return false
    }

    private fun expectLeftBraceReturning(owner: String): Token? = expect(
        TokenType.LEFT_BRACE,
        "expected '{' to open the $owner but found ${current().describe()}",
    )

    private fun expectLeftParen(owner: String): Boolean {
        if (at(TokenType.LEFT_PAREN)) {
            advance()
            return true
        }
        error("expected '(' after $owner but found ${current().describe()}", current().span)
        return false
    }

    private fun expectRightParen(owner: String): Token? = expect(
        TokenType.RIGHT_PAREN,
        "expected ')' to close the $owner but found ${current().describe()}",
    )

    private fun expectRightBrace(owner: String): Token? = expect(
        TokenType.RIGHT_BRACE,
        "expected '}' to close the $owner but found ${current().describe()}",
    )

    private fun expectLeftBracket(owner: String): Boolean {
        if (at(TokenType.LEFT_BRACKET)) {
            advance()
            return true
        }
        error("expected '[' after $owner but found ${current().describe()}", current().span)
        return false
    }

    private fun expectRightBracket(owner: String): Token? = expect(
        TokenType.RIGHT_BRACKET,
        "expected ']' to close the $owner but found ${current().describe()}",
    )

    private fun error(message: String, span: Span) {
        diagnostics.error(message, span)
    }

    // --------------------------------------------------------------- recovery

    /** Skips to the beginning of the next top-level declaration. */
    private fun synchronizeToDeclaration() {
        while (!at(TokenType.END_OF_FILE)) {
            if (at(TokenType.NEWLINE)) {
                advance()
                skipNewlines()
                // `meow` belongs here as much as `class` does: it opens a declaration, so stopping
                // only at `class` would report the *next* one as malformed too.
                if (at(TokenType.MEOW) || at(TokenType.CLASS) || isModifier(current().type)) return
                continue
            }
            if (at(TokenType.MEOW) || at(TokenType.CLASS)) return
            advance()
        }
    }

    /** Skips to the next class-member boundary. */
    private fun synchronizeToMember() {
        var depth = 0
        while (!at(TokenType.END_OF_FILE)) {
            when (current().type) {
                TokenType.LEFT_BRACE -> depth++
                TokenType.RIGHT_BRACE -> if (depth == 0) return else depth--
                TokenType.NEWLINE -> if (depth == 0) {
                    advance()
                    skipNewlines()
                    return
                }

                else -> Unit
            }
            advance()
        }
    }

    /**
     * Skips to the next entry of a `meow class` header, i.e. past a comma or up to the `)`.
     *
     * A header list is not a statement sequence, so the statement and member boundaries would both
     * run past the end of the list and swallow the class body with it. Stopping at the comma or the
     * closing parenthesis is what keeps one malformed entry to one diagnostic.
     */
    private fun synchronizeToMeowField() {
        var depth = 0
        while (!at(TokenType.END_OF_FILE)) {
            when (current().type) {
                TokenType.LEFT_PAREN, TokenType.LEFT_BRACKET -> depth++
                TokenType.RIGHT_PAREN, TokenType.RIGHT_BRACKET -> {
                    if (depth == 0) return
                    depth--
                }

                TokenType.COMMA -> if (depth == 0) {
                    advance()
                    skipNestedNewlines()
                    return
                }

                else -> Unit
            }
            advance()
        }
    }

    /** Skips to the next statement boundary. */
    private fun synchronizeToStatement() {
        var depth = 0
        while (!at(TokenType.END_OF_FILE)) {
            when (current().type) {
                TokenType.LEFT_PAREN, TokenType.LEFT_BRACKET, TokenType.LEFT_BRACE -> depth++
                TokenType.RIGHT_PAREN, TokenType.RIGHT_BRACKET -> if (depth > 0) depth--
                TokenType.RIGHT_BRACE -> if (depth == 0) return else depth--
                TokenType.NEWLINE -> if (depth == 0) {
                    advance()
                    skipNewlines()
                    return
                }

                else -> Unit
            }
            advance()
        }
    }

    /**
     * Skips to the next `when` branch, i.e. to a newline that is followed by a value or by `}`.
     *
     * Recovery cannot simply stop at the next newline: an arm body written as a block contains
     * newlines of its own, so nesting is tracked and only a boundary at depth zero counts.
     */
    private fun synchronizeToWhenBranch() {
        var depth = 0
        while (!at(TokenType.END_OF_FILE)) {
            when (current().type) {
                TokenType.LEFT_PAREN, TokenType.LEFT_BRACKET, TokenType.LEFT_BRACE -> depth++
                TokenType.RIGHT_PAREN, TokenType.RIGHT_BRACKET -> if (depth > 0) depth--
                TokenType.RIGHT_BRACE -> {
                    if (depth == 0) return
                    depth--
                }

                TokenType.NEWLINE -> if (depth == 0) {
                    advance()
                    skipNewlines()
                    return
                }

                else -> Unit
            }
            advance()
        }
    }

    private companion object {
        /**
         * Keywords that may also appear as a package-name segment.
         *
         * Only words that cannot begin a declaration need listing: a package segment is read after
         * `locate` and after a `.`, where no statement could start, so accepting one costs no
         * ambiguity. The list is deliberately short — `class`, `fun` and `interface` are excluded so
         * that a malformed `locate` still fails at the word that reveals the mistake.
         */
        val NAME_SEGMENT_KEYWORDS: Set<TokenType> = setOf(
            TokenType.EMIT,
            TokenType.NYA,
            TokenType.NYAN,
            TokenType.SCRATCH,
            TokenType.YIELD,
            TokenType.ELSE,
            TokenType.THIS,
            TokenType.TRUE,
            TokenType.FALSE,
            TokenType.VAL,
            TokenType.VAR,
        )
    }
}
