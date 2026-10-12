package com.github.kusa233.neko.parser

import com.github.kusa233.neko.ast.NekoFile
import com.github.kusa233.neko.ast.ClassDeclaration
import com.github.kusa233.neko.ast.FunctionDeclaration
import com.github.kusa233.neko.ast.LambdaExpression
import com.github.kusa233.neko.ast.ReturnStatement
import com.github.kusa233.neko.ast.WhenStatement
import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.source.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the parser.
 *
 * Two things matter here beyond "it builds a tree": that the tree has the shape the language
 * prescribes, and that a malformed program produces *useful* diagnostics instead of a cascade.
 */
class ParserTest {

    private fun parse(source: String): Pair<NekoFile, DiagnosticBag> {
        val diagnostics = DiagnosticBag()
        val file = Parser(SourceFile.of("Test.neko", source), diagnostics).parseFile()
        return file to diagnostics
    }

    private fun classOf(source: String): ClassDeclaration {
        val (file, diagnostics) = parse(source)
        assertTrue(
            !diagnostics.hasErrors,
            "expected a clean parse, but got:\n" +
                diagnostics.diagnostics.joinToString("\n") { it.toString() },
        )
        return file.classes.single()
    }

    private fun function(source: String, name: String): FunctionDeclaration =
        classOf(source).functions.single { it.name == name }

    /**
     * The statements of a function that must have a body.
     *
     * `FunctionDeclaration.body` is nullable because a function declared in an interface may omit it.
     * Every test that reaches for the body is about a function that has one, so the assertion is made
     * once here rather than at each call site. It is named separately from `body` because a member
     * always wins over an extension of the same name.
     */
    private val FunctionDeclaration.statements: List<com.github.kusa233.neko.ast.Statement>
        get() = requireNotNull(body) { "function '$name' was expected to have a body" }.statements

    @Test
    fun `parses a package, a class and its members`() {
        val file = classOf(
            """
            locate com.example.thing

            class Sample {
                private val field: int = 1
                fun method() {
                }
            }
            """.trimIndent() + "\n",
        )
        assertEquals("Sample", file.name)
        assertEquals(1, file.fields.size)
        assertEquals("field", file.fields.single().name)
        assertEquals(1, file.functions.size)
        assertEquals("method", file.functions.single().name)
    }

    @Test
    fun `parses the package into its segments`() {
        val (file, _) = parse("locate com.example.thing\n\nclass Sample {\n}\n")
        assertEquals(listOf("com", "example", "thing"), file.packageDeclaration?.segments)
    }

    @Test
    fun `a package segment may be spelled like a keyword`() {
        // The standard library lives in `com.github.kusa233.neko.stdlib.emit`, and `emit` is a
        // keyword. A package name is a name rather than a use of the grammar, so `locate` must read
        // it — otherwise the library could not be written in the package the sample's spec implies.
        val (file, diagnostics) = parse("locate com.example.emit\n\nclass Sample {\n}\n")
        assertTrue(
            !diagnostics.hasErrors,
            "expected a clean parse, but got:\n" +
                diagnostics.diagnostics.joinToString("\n") { it.toString() },
        )
        assertEquals(listOf("com", "example", "emit"), file.packageDeclaration?.segments)
    }

    @Test
    fun `a declaration without 'val' or 'var' is mutable`() {
        // This is the rule that makes the sample's line 18 legal.
        val function = function(
            """
            class Sample {
                fun method() {
                    value: int = 1
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val declaration = function.statements
            .filterIsInstance<com.github.kusa233.neko.ast.VariableDeclarationStatement>()
            .single()
        assertTrue(
            declaration.kind == com.github.kusa233.neko.ast.VariableKind.VAR,
            "a declaration with no keyword must be mutable, but was ${declaration.kind}",
        )
    }

    @Test
    fun `parses a lambda with its parameters and body`() {
        val function = function(
            """
            class Sample {
                fun method() {
                    f: [int, int] -> int = { x, y ->
                        yield x + y
                    }
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val declaration = function.statements
            .filterIsInstance<com.github.kusa233.neko.ast.VariableDeclarationStatement>()
            .single()
        val lambda = declaration.initializer as LambdaExpression
        assertEquals(listOf("x", "y"), lambda.parameters.map { it.name })
    }

    @Test
    fun `parses a when statement with a value branch and an else branch`() {
        val function = function(
            """
            class Sample {
                fun method(a: int) {
                    when (a) {
                        1 -> println("one")
                        else -> println("other")
                    }
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val whenStatement = function.statements.filterIsInstance<WhenStatement>().single()
        assertEquals(2, whenStatement.branches.size)
        assertTrue(whenStatement.hasElseBranch, "the else branch should be recorded")
        assertTrue(whenStatement.branches.last().condition == null, "else has no condition")
    }

    @Test
    fun `parses a when branch whose body is a block`() {
        val function = function(
            """
            class Sample {
                fun method(a: int) {
                    when (a) {
                        2 -> {
                            println("two")
                        }
                    }
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val whenStatement = function.statements.filterIsInstance<WhenStatement>().single()
        assertTrue(
            whenStatement.branches.single().body is com.github.kusa233.neko.ast.BlockStatement,
            "a braced branch body should parse as a block",
        )
    }

    @Test
    fun `parses an emit clause written with square brackets`() {
        val function = function(
            """
            class Sample {
                fun method() {
                    raise 1, 2
                } emit [int, string]
            }
            """.trimIndent() + "\n",
            "method",
        )
        assertTrue(function.hasEmitClause, "the clause should be recorded as present")
        assertEquals(
            listOf("int", "string"),
            function.emitTypes.map { (it as com.github.kusa233.neko.ast.NamedTypeReference).name },
        )
    }

    @Test
    fun `an emit clause naming no values is still an emit clause`() {
        // `emit []` is legal and distinct from having no clause at all: it says the function may
        // leave early, matching a caller's `try f() { -> ... }`.
        val function = function(
            """
            class Sample {
                fun method() {
                    raise
                } emit []
            }
            """.trimIndent() + "\n",
            "method",
        )
        assertTrue(function.hasEmitClause, "'emit []' must count as a clause")
        assertTrue(function.emitTypes.isEmpty(), "the clause names no values")
    }

    @Test
    fun `an emit clause belongs to the function, not to the enclosing class`() {
        // The clause follows the closing brace of the body, which is easy to attach one level too
        // high; if that happened, the next function would silently inherit it.
        val enclosing = classOf(
            """
            class Sample {
                fun first() {
                    raise 1
                } emit [int]

                fun second() {
                }
            }
            """.trimIndent() + "\n",
        )
        assertEquals(1, enclosing.functions.single { it.name == "first" }.emitTypes.size)
        assertTrue(
            !enclosing.functions.single { it.name == "second" }.hasEmitClause,
            "an 'emit' clause must not leak onto the following function",
        )
    }

    @Test
    fun `parses a try with catch and finally`() {
        val function = function(
            """
            class Sample {
                fun method() {
                    try {
                        println("body")
                    } catch (e: CustomException) {
                        println("caught")
                    } finally {
                        println("finally")
                    }
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val statement = function.statements
            .filterIsInstance<com.github.kusa233.neko.ast.TryCatchStatement>()
            .single()
        assertEquals("e", statement.catches.single().parameterName)
        assertTrue(statement.finallyBlock != null, "the finally block should be recorded")
    }

    @Test
    fun `parses a class header with a superclass and an interface`() {
        val declaration = classOf(
            """
            class Sample: Base(), Marker {
                fun method() {
                }
            }
            """.trimIndent() + "\n",
        )
        assertEquals("Base", declaration.superclass?.name)
        assertEquals(listOf("Marker"), declaration.interfaces.map { it.name })
    }

    @Test
    fun `parses an interface with abstract and default methods`() {
        val declaration = classOf(
            """
            interface Sample {
                fun required(o1: int)
                fun provided() {
                    println("default")
                }
            }
            """.trimIndent() + "\n",
        )
        assertTrue(declaration.isInterface, "the declaration should be recorded as an interface")
        assertTrue(
            declaration.functions.single { it.name == "required" }.isAbstract,
            "a function with no body must be abstract",
        )
        assertTrue(
            !declaration.functions.single { it.name == "provided" }.isAbstract,
            "a function with a body is a default method",
        )
    }

    @Test
    fun `parses a constructor declaration`() {
        val declaration = classOf(
            """
            class Sample {
                constructor(value: int) {
                    println(value)
                }
            }
            """.trimIndent() + "\n",
        )
        assertEquals(listOf("value"), declaration.constructors.single().parameters.map { it.name })
    }

    @Test
    fun `parses index reads and writes`() {
        val function = function(
            """
            class Sample {
                fun method(values: int[]) {
                    println(values[0])
                    values[1] = 2
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val read = function.statements
            .filterIsInstance<com.github.kusa233.neko.ast.ExpressionStatement>()
            .first()
            .expression as com.github.kusa233.neko.ast.CallExpression
        val index = read.arguments.single() as com.github.kusa233.neko.ast.IndexExpression
        assertEquals(0, (index.index as com.github.kusa233.neko.ast.IntegerLiteral).value)

        val write = function.statements
            .filterIsInstance<com.github.kusa233.neko.ast.IndexAssignmentStatement>()
            .single()
        assertEquals(1, (write.index as com.github.kusa233.neko.ast.IntegerLiteral).value)
    }

    @Test
    fun `parses a chained comparison as one expression`() {
        val function = function(
            """
            class Sample {
                fun method(a: int, b: int, c: int): bool {
                    return a > b > c
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val returned = function.statements.filterIsInstance<ReturnStatement>().single()
        val chain = returned.value as com.github.kusa233.neko.ast.ChainedComparisonExpression
        assertEquals(3, chain.operands.size)
        assertEquals(2, chain.operators.size)
    }

    @Test
    fun `an if-else parses both branches`() {
        val function = function(
            """
            class Sample {
                fun method(a: bool) {
                    if (a) {
                        println("yes")
                    } else {
                        println("no")
                    }
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val ifStatement = function.statements
            .filterIsInstance<com.github.kusa233.neko.ast.IfStatement>()
            .single()
        assertTrue(ifStatement.elseBranch != null, "the else branch should be parsed")
    }

    @Test
    fun `multiplication binds tighter than addition`() {
        val function = function(
            """
            class Sample {
                fun method(): int {
                    return 1 + 2 * 3
                }
            }
            """.trimIndent() + "\n",
            "method",
        )
        val returned = function.statements.filterIsInstance<ReturnStatement>().single()
        // `1 + (2 * 3)`: the addition is the root, and its right operand is a multiplication.
        val addition = returned.value as com.github.kusa233.neko.ast.BinaryExpression
        assertEquals(
            "*",
            (addition.right as com.github.kusa233.neko.ast.BinaryExpression).operator.display,
        )
    }

    @Test
    fun `reports a missing closing brace once, not once per line`() {
        val (_, diagnostics) = parse(
            """
            class Sample {
                fun method() {
                    println("a")
                    println("b")
                    println("c")
            """.trimIndent() + "\n",
        )
        val errors = diagnostics.diagnostics.filter { it.severity.name == "ERROR" }
        assertTrue(errors.isNotEmpty(), "an unclosed brace must be reported")
        assertTrue(
            errors.size <= 2,
            "recovery should not cascade into ${errors.size} errors:\n" +
                errors.joinToString("\n") { it.toString() },
        )
    }

    @Test
    fun `reports a missing closing parenthesis`() {
        val (_, diagnostics) = parse(
            """
            class Sample {
                fun method() {
                    println("a"
                }
            }
            """.trimIndent() + "\n",
        )
        val error = diagnostics.diagnostics.single { it.severity.name == "ERROR" }
        assertTrue(
            error.message.contains("')'"),
            "the message should name the expected token, but was: ${error.message}",
        )
        // Whatever the parser anchors on, it must be inside the offending statement rather than at
        // the end of the file.
        val line = error.span?.line
        assertTrue(
            line != null && line in 3..4,
            "expected the error on the println line, but it was at line $line",
        )
    }

    @Test
    fun `recovers from one bad function and still parses the next`() {
        val (file, diagnostics) = parse(
            """
            class Sample {
                fun broken() {
                    val = = 1
                }

                fun fine() {
                    println("ok")
                }
            }
            """.trimIndent() + "\n",
        )
        assertTrue(diagnostics.hasErrors, "the broken function must be reported")
        val enclosing = file.classes.single()
        // `fine` must still be present, so a later pass can report about it rather than the compiler
        // giving up on the whole class after the first mistake.
        assertTrue(
            enclosing.functions.any { it.name == "fine" },
            "parsing should recover at the next function declaration; found " +
                enclosing.functions.map { it.name },
        )
        // The recovery must be quiet: one bad statement should not produce a wall of errors.
        val errors = diagnostics.diagnostics.filter { it.severity.name == "ERROR" }
        assertTrue(
            errors.size <= 2,
            "expected at most 2 errors from one bad statement, got ${errors.size}:\n" +
                errors.joinToString("\n") { it.toString() },
        )
    }

    @Test
    fun `rejects a top-level statement that is not a declaration`() {
        val (_, diagnostics) = parse(
            """
            locate com.example

            println("not allowed at the top level")
            """.trimIndent() + "\n",
        )
        assertTrue(
            diagnostics.hasErrors,
            "a bare statement outside a class must not be accepted",
        )
    }

    @Test
    fun `records a span on every function`() {
        val enclosing = classOf("class Sample {\n    fun method() {\n    }\n}\n")
        val function = enclosing.functions.single()
        assertEquals(2, function.nameSpan.line, "the name span should point at the declaration")
    }
}
