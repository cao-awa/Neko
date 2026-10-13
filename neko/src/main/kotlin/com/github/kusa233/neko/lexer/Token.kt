package com.github.kusa233.neko.lexer

import com.github.kusa233.neko.source.Span

/** Every kind of lexical token the Neko grammar recognises. */
enum class TokenType(val display: String) {
    IDENTIFIER("identifier"),
    INTEGER_LITERAL("integer literal"),
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
    RAISE("'raise'"),
    EMIT("'emit'"),
    TRY("'try'"),
    CATCH("'catch'"),
    FINALLY("'finally'"),

    // Expressions.
    THIS("'this'"),

    // Literals.
    TRUE("'true'"),
    FALSE("'false'"),

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

    val isLiteral: Boolean get() = this == INTEGER_LITERAL || this == STRING_LITERAL || this == TRUE || this == FALSE
}

/** One lexed token, together with the exact [span] it occupies in its source file. */
class Token(
    val type: TokenType,
    /** Exact source text of the token. */
    val text: String,
    val span: Span,
    /** Decoded value for [TokenType.INTEGER_LITERAL] tokens. */
    val integerValue: Long = 0L,
    /** Decoded value for [TokenType.STRING_LITERAL] tokens. */
    val stringValue: String = "",
) {
    val line: Int get() = span.line
    val column: Int get() = span.column

    /** Human-readable description used in "expected X but found Y" messages. */
    fun describe(): String = when (type) {
        TokenType.IDENTIFIER -> "identifier '$text'"
        TokenType.INTEGER_LITERAL -> "integer literal '$text'"
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
    "raise" to TokenType.RAISE,
    "emit" to TokenType.EMIT,
    "try" to TokenType.TRY,
    "catch" to TokenType.CATCH,
    "finally" to TokenType.FINALLY,
    "this" to TokenType.THIS,
    "true" to TokenType.TRUE,
    "false" to TokenType.FALSE,
)
