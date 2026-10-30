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
    /**
     * `'miao'` — the prefix that turns a class declaration into an enum.
     *
     * `miao class Color { RED, GREEN }` declares one fixed instance per name, and the compiler emits
     * the constant fields, the array, `values()` and `valueOf()` from that list. It sits beside
     * [MEOW] and [CLASS] for the same reason `meow` does: it can only ever introduce a class
     * declaration, and the parser reports a `miao` that introduces anything else.
     */
    MIAO("'miao'"),
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
    /**
     * `'for'` — the classic three-part loop, written exactly as Java writes it.
     *
     * `for (i: int = 0; i < 100; i++) { ... }` has the same four pieces as Java's: an initialization
     * that declares a variable, a condition checked before each pass, an update run after each pass,
     * and a body. The variable it declares lives in the loop's own scope, so a second `for` may reuse
     * the name and nothing leaks after the loop.
     *
     * The same keyword also opens the for-each form, `for (element: int in list) { ... }`, which is
     * told apart by the `in` that follows the declared type. Both are `for` because both say "run this
     * for each step of a sequence"; the bodies are identical and only how the sequence is produced
     * differs.
     */
    FOR("'for'"),
    /**
     * `'in'` — the for-each separator: `for (element: int in list)`.
     *
     * A keyword rather than an identifier because a statement may not be guessed at: seeing `in` is
     * what tells the parser the `for` it is reading is the for-each form rather than the three-part
     * one, and both start with the same tokens up to the type.
     */
    IN("'in'"),
    WHEN("'when'"),
    /**
     * `'nya'` — checks that a condition holds, and either runs a recovery block or is worth a value
     * when it fails.
     *
     * `nya a == b` throws an `AssertionError` when the condition fails. `nya a == b { ... }` runs the
     * block instead, and the block catches an `AssertionError` the condition *threw* as well as a
     * condition that came back false — so `nya method() { ... }` is a call whose failure is handled,
     * and the condition is not required to be a `bool` when a block spells out what failing means.
     * `nya a == b { ... } else { ... }` is worth whatever the `else` yields when the condition holds.
     * Written as a word rather than a function because it is not a call: the branch it lowers to
     * belongs to the statement it appears in, the way `if`'s does. A bare `nya` is compiled to the
     * shape Java's `assert` uses — a branch past the throw — but it is deliberately *not* gated on the
     * JVM's `-ea` flag, which is the one thing it does differ in: a check that silently does nothing
     * is worse than no check, and Neko has no flag to turn one on with.
     */
    NYA("'nya'"),
    /**
     * `'scratch'` — hands the values it names to the innermost `try` handler that declared a
     * matching `emit [...]` signature.
     *
     * The word is the sample's: a `scratch` is where the program says "this is the point I would
     * have had to unwind from", and the handler it reaches is generated as a method rather than
     * travelled to by an exception.
     */
    SCRATCH("'scratch'"),
    /**
     * `'nyan'` — leaves the enclosing `try` handler, answering the value it names.
     *
     * The counterpart of `scratch` inside a handler: `scratch` enters one, `nyan` leaves it with a
     * value, and `yield` leaves it by throwing the value to the `try` that declared it.
     */
    NYAN("'nyan'"),
    EMIT("'emit'"),
    /**
     * `'blep'` — declares one branch of a `try pre` handler group, keyed on a signal.
     *
     * `blep: label NekoSignal.ADD { a, b -> nyan a + b }` adds to the group called `label` a branch
     * that answers whenever a `scratch` names `NekoSignal.ADD` as its signal. The whole group is
     * installed by one `try call(...) { ... }: label`, and its branches become the `if`s of the
     * generated `dispatch`, with that `try`'s own body as the `else`.
     */
    BLEP("'blep'"),
    /**
     * `'signal'` — names the enum constant a `scratch` is asking for.
     *
     * `scratch 10, 5 signal NekoSignal.ADD` reaches the handler's `dispatch` with that constant as
     * its last argument, which is how one handler answers several different operations. The word is
     * optional: without it the constant passed is `null`, no `blep` branch matches, and the group
     * answers with the body the installing `try` wrote — so a plain `scratch` keeps meaning exactly
     * what it did before signals existed.
     */
    SIGNAL("'signal'"),
    /**
     * `'try'` — now only the extractor form, `try f(...) { a -> ... }`, which declares the handler a
     * `scratch` reaches and catches the `EmitYieldException` a `yield` throws.
     *
     * The `try { ... } catch ...` statement that handled a JVM throwable was removed: `nya`'s block
     * form replaces it for the failure a Neko program can actually name, because a bare `nya` is what
     * throws one and a block is what answers it.
     */
    TRY("'try'"),
    /**
     * The four locking keywords, backed by a `java.util.concurrent.locks.StampedLock`.
     *
     * They are two independent choices — *which* lock, and *which* mode — spelled as four words:
     *
     * | Written | Lock | Mode |
     * | --- | --- | --- |
     * | `purr { ... }` | instance | write |
     * | `purred { ... }` | instance | read |
     * | `purrcls { ... }` | class | write |
     * | `purredcls { ... }` | class | read |
     *
     * The instance lock is what `synchronized (this)` would be, taken in write mode by default; the
     * class lock is the one-per-class lock `synchronized (Cls.class)` gives. A read lock lets many
     * threads in at once, which is what makes a read-mostly field worth guarding this way: only a
     * writer is exclusive.
     *
     * Words rather than a call because this is not a call: the lock has to be released on every way
     * out of the block — falling off the end, `return`, `nyan`, `yield`, and a thrown exception — and
     * only the compiler can emit that. `StampedLock` makes the compiler's involvement unavoidable
     * anyway: unlocking requires the *stamp* the acquisition answered, and it is not reentrant.
     *
     * Four keywords rather than one keyword plus modifiers because `read` and `class` are ordinary
     * identifiers in this language — `purr class { ... }` would have to read `class` as a keyword in
     * that one position and as a name everywhere else, which is the kind of context-dependent lexing
     * a reader cannot see. A single word per form has no such ambiguity.
     */
    PURR("'purr'"),
    PURRED("'purred'"),
    PURRCLS("'purrcls'"),
    PURREDCLS("'purredcls'"),

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
    /**
     * `'::'` — the separator in `value::generic`.
     *
     * Its own token rather than two [COLON]s, because the two characters are a single operator here:
     * `:` already introduces a type announcement (`x: int`), and `a::b` is not a `:` followed by
     * something that begins with `:`.
     */
    COLON_COLON("'::'"),
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

    /**
     * `++` and `--`: add one to, or subtract one from, a variable, in place.
     *
     * Both are statements, not expressions — there is no value to read back. That is the same choice
     * `i++` in a `for`'s update position needs and no more: an expression form would have to decide
     * whether it answers the old or the new value, and nothing in this language would use the answer.
     */
    PLUS_PLUS("'++'"),
    MINUS_MINUS("'--'"),

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
    "miao" to TokenType.MIAO,
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
    "for" to TokenType.FOR,
    "in" to TokenType.IN,
    "when" to TokenType.WHEN,
    "nya" to TokenType.NYA,
    "scratch" to TokenType.SCRATCH,
    "nyan" to TokenType.NYAN,
    "emit" to TokenType.EMIT,
    "blep" to TokenType.BLEP,
    "signal" to TokenType.SIGNAL,
    "try" to TokenType.TRY,
    "purr" to TokenType.PURR,
    "purred" to TokenType.PURRED,
    "purrcls" to TokenType.PURRCLS,
    "purredcls" to TokenType.PURREDCLS,
    "this" to TokenType.THIS,
    "true" to TokenType.TRUE,
    "false" to TokenType.FALSE,
    "null" to TokenType.NULL,
)
