package com.github.kusa233.neko.lexer

import com.github.kusa233.neko.source.Span

/** Every kind of lexical token the Neko grammar recognises. */
enum class TokenType(val display: String) {
    IDENTIFIER("identifier"),
    INTEGER_LITERAL("integer literal"),
    FLOAT_LITERAL("floating-point literal"),
    CHAR_LITERAL("character literal"),
    STRING_LITERAL("string literal"),

    // Declarations and modifiers.
    LOCATE("'locate'"),
    IMPORT("'import'"),
    /**
     * `'meow'` — the prefix that turns a class declaration into one whose fields are declared in its
     * header and whose `equals`/`hashCode` are derived from them.
     *
     * It sits beside [CLASS] rather than among the statements because it can only ever introduce a
     * class declaration; the parser reports a `meow` that introduces anything else.
     */
    MEOW("'meow'"),
    CLASS("'class'"),
    INTERFACE("'interface'"),
    FUN("'fun'"),
    CONSTRUCTOR("'constructor'"),
    VAL("'val'"),
    VAR("'var'"),
    PRIVATE("'private'"),
    PUBLIC("'public'"),
    PROTECTED("'protected'"),
    STATIC("'static'"),

    // Statements.
    RETURN("'return'"),
    YIELD("'yield'"),
    IF("'if'"),
    ELSE("'else'"),
    WHILE("'while'"),
    WHEN("'when'"),
    /**
     * `'nya'` — checks that a condition holds, and either runs a recovery block or is worth a value
     * when it does not.
     *
     * `nya a == b` throws an `AssertionError` when the condition fails; `nya a == b { ... }` runs the
     * block instead of throwing, and `nya a == b { ... } else { ... }` is worth whatever the `else`
     * yields when the condition holds. Written as a word rather than a function because it is not a
     * call: the branch it lowers to belongs to the statement it appears in, the way `if`'s does.
     *
     * It is deliberately *not* gated on the JVM's `-ea` flag, which is what separates it from Java's
     * `assert`: a check that silently does nothing is worse than no check, and Neko has no flag to
     * turn one on with.
     */
    NYA("'nya'"),
    RAISE("'raise'"),
    EMIT("'emit'"),
    /**
     * `'try'` — now only the extractor form, `try f(...) { a -> ... }`, which receives a `raise`.
     *
     * The `try { ... } catch ...` statement that handled a JVM throwable was removed: `nya`'s block
     * form replaces it for the case a Neko program can actually name, because the failure it
     * describes is one the program itself asserted rather than one arriving from outside.
     */
    TRY("'try'"),

    // Expressions.
    THIS("'this'"),

    // Literals.
    TRUE("'true'"),
    FALSE("'false'"),
    /**
     * `'null'` — the one value a nullable type exists to hold.
     *
     * It is a literal rather than an identifier so that it cannot be shadowed by a variable named
     * `null`, which would make `null` mean two different things in the same scope.
     */
    NULL("'null'"),

    // Punctuation.
    LEFT_BRACE("'{'"),
    RIGHT_BRACE("'}'"),
    LEFT_PAREN("'('"),
    RIGHT_PAREN("')'"),
    LEFT_BRACKET("'['"),
    RIGHT_BRACKET("']'"),
    COMMA("','"),
    COLON("':'"),
    DOT("'.'"),
    SEMICOLON("';'"),
    ARROW("'->'"),
    /**
     * `'?'` — the suffix that makes a type nullable, and the first half of the safe call `'?.'`.
     *
     * One token serves both because `?.` is lexed as this followed by [DOT]: the two are only
     * distinguishable by the character that follows, which the parser can see just as well as the
     * lexer can.
     */
    QUESTION("'?'"),

    // Operators.
    ASSIGN("'='"),
    PLUS("'+'"),
    MINUS("'-'"),
    STAR("'*'"),
    SLASH("'/'"),
    PERCENT("'%'"),
    EQUALS_EQUALS("'=='"),
    BANG_EQUALS("'!='"),
    LESS("'<'"),
    LESS_EQUALS("'<='"),
    GREATER("'>'"),
    GREATER_EQUALS("'>='"),
    AND_AND("'&&'"),
    OR_OR("'||'"),
    BANG("'!'"),

    /** Physical end of line; significant as a statement terminator. */
    NEWLINE("end of line"),

    /** Synthetic token marking the end of the token stream. */
    END_OF_FILE("end of file"),
    ;

    /**
     * Whether this token is a literal of some kind.
     *
     * Every literal kind has to be listed, because a caller that asks this question means "is a
     * written value here?" and an unlisted kind answers no.
     */
    val isLiteral: Boolean
        get() = this == INTEGER_LITERAL || this == FLOAT_LITERAL || this == CHAR_LITERAL ||
            this == STRING_LITERAL || this == TRUE || this == FALSE || this == NULL
}

/** One lexed token, together with the exact [span] it occupies in its source file. */
class Token(
    val type: TokenType,
    /** Exact source text of the token. */
    val text: String,
    val span: Span,
    /**
     * Decoded value of an [TokenType.INTEGER_LITERAL] or [TokenType.CHAR_LITERAL] token.
     *
     * The two share a field because they are the same fact — a whole number the source spelled — and
     * a character literal is one written in quotes. A literal that does not fit the widest type the
     * language has still carries its clamped value, so the parser has something to build a node from
     * and the user gets the one diagnostic the lexer already reported rather than a crash.
     */
    val literalValue: Long = 0L,
    /** Decoded value of a [TokenType.FLOAT_LITERAL] token. */
    val floatValue: Double = 0.0,
    /** `true` when a [TokenType.FLOAT_LITERAL] asked for `float` with an `f`/`F` suffix. */
    val isFloatSuffixed: Boolean = false,
    /** Decoded value for [TokenType.STRING_LITERAL] tokens. */
    val stringValue: String = "",
) {
    val line: Int get() = span.line
    val column: Int get() = span.column

    /** Human-readable description used in "expected X but found Y" messages. */
    fun describe(): String = when (type) {
        TokenType.IDENTIFIER -> "identifier '$text'"
        TokenType.INTEGER_LITERAL -> "integer literal '$text'"
        TokenType.FLOAT_LITERAL -> "floating-point literal '$text'"
        TokenType.CHAR_LITERAL -> "character literal '$text'"
        TokenType.STRING_LITERAL -> "string literal"
        else -> type.display
    }

    override fun toString(): String = "${type.name}(${text.replace("\n", "\\n")})@$line:$column"
}

/** The reserved words of Neko, mapped to their token types. */
internal val KEYWORDS: Map<String, TokenType> = mapOf(
    "locate" to TokenType.LOCATE,
    "import" to TokenType.IMPORT,
    "meow" to TokenType.MEOW,
    "class" to TokenType.CLASS,
    "interface" to TokenType.INTERFACE,
    "fun" to TokenType.FUN,
    "constructor" to TokenType.CONSTRUCTOR,
    "val" to TokenType.VAL,
    "var" to TokenType.VAR,
    "private" to TokenType.PRIVATE,
    "public" to TokenType.PUBLIC,
    "protected" to TokenType.PROTECTED,
    "static" to TokenType.STATIC,
    "return" to TokenType.RETURN,
    "yield" to TokenType.YIELD,
    "if" to TokenType.IF,
    "else" to TokenType.ELSE,
    "while" to TokenType.WHILE,
    "when" to TokenType.WHEN,
    "nya" to TokenType.NYA,
    "raise" to TokenType.RAISE,
    "emit" to TokenType.EMIT,
    "try" to TokenType.TRY,
    "this" to TokenType.THIS,
    "true" to TokenType.TRUE,
    "false" to TokenType.FALSE,
    "null" to TokenType.NULL,
)
