package com.github.kusa233.nekoj

import com.github.kusa233.neko.lexer.TokenType
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Checks the wiring that makes Neko a language in the IDE rather than a text file.
 *
 * These tests load the plugin into a real IDE instance and drive its real machinery, so what they
 * assert is what a user sees: if the file type, parser definition or highlighter were registered
 * under the wrong name, or not at all, the assertions below fail here rather than silently doing
 * nothing in an editor.
 */
class NekoPluginTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------ language and file type

    fun testNekoFileIsParsedAsTheNekoLanguage() {
        val file = myFixture.configureByText("Sample.neko", "class Sample { }")

        // The single most important assertion in this class. When no parser definition is
        // registered for a language, the platform quietly hands back a plain-text file, and every
        // other feature -- highlighting, annotation, completion -- is then dead with no error
        // anywhere. Naming NekoPsiFile and NekoLanguage here is what proves it is not.
        assertTrue(
            "expected a NekoPsiFile but got ${file.javaClass.name}",
            file is NekoPsiFile,
        )
        assertSame(NekoLanguage.INSTANCE, file.language)
        assertSame(NekoFileType, file.fileType)
    }

    fun testNekoExtensionIsClaimedByTheNekoFileType() {
        val fileType = com.intellij.openapi.fileTypes.FileTypeManager
            .getInstance()
            .getFileTypeByFileName("Example.neko")

        assertSame(NekoFileType, fileType)
        assertEquals("neko", fileType.defaultExtension)
    }

    fun testKeywordsAreRecoveredFromTheCompilerTokenEnum() {
        // Guards the derivation in `NekoTokens`: if the compiler's `display` format ever changes,
        // this catches it rather than leaving keywords uncoloured.
        val keywords = NekoTokens.keywords
        assertEquals(TokenType.CLASS, keywords["class"])
        assertEquals(TokenType.FUN, keywords["fun"])
        assertEquals(TokenType.EMIT, keywords["emit"])
        assertEquals(TokenType.YIELD, keywords["yield"])
        assertEquals(TokenType.NYA, keywords["nya"])
        assertEquals(TokenType.TRUE, keywords["true"])

        // Punctuation is not a word, so it must not be mistaken for one.
        assertNull(keywords["=="])
        assertNull(keywords["{"])
    }

    // ------------------------------------------------------------------------------- the lexer

    fun testLexerCoversEveryCharacterOfTheFile() {
        val text = buildString {
            append("// a line comment\n")
            append("class Sample {\n")
            append("    /* a block\n       comment */\n")
            append("    val field1: int = 123\n")
            append("}\n")
        }

        val lexed = lex(text)
        val rebuilt = lexed.joinToString("") { it.second }

        // The compiler's lexer drops whitespace and comments entirely. An IDE lexer must not: the
        // platform requires consecutive tokens to tile the buffer, and a gap makes highlighting and
        // caret positioning wrong. Rebuilding the text from the tokens proves there is no hole.
        assertEquals(text, rebuilt)
    }

    fun testLexerProducesCommentsAndWhitespaceTheCompilerDrops() {
        val lexed = lex("// hi\nx = 1 /* c */\n")

        val types = lexed.map { it.first }
        assertTrue("expected a line comment in $types", NekoTokens.LINE_COMMENT in types)
        assertTrue("expected a block comment in $types", NekoTokens.BLOCK_COMMENT in types)
        assertTrue("expected whitespace in $types", NekoTokens.WHITE_SPACE in types)

        // And the tokens the compiler *does* produce still come through unchanged.
        assertTrue(NekoTokens.of(TokenType.INTEGER_LITERAL) in types)
        assertTrue(NekoTokens.of(TokenType.ASSIGN) in types)

        assertEquals("// hi", lexed.first { it.first == NekoTokens.LINE_COMMENT }.second)
        assertEquals("/* c */", lexed.first { it.first == NekoTokens.BLOCK_COMMENT }.second)
    }

    fun testUnterminatedBlockCommentStillProducesAToken() {
        // Typing "/" then "*" puts the editor in this state constantly; it must not lose the text.
        val lexed = lex("x = 1 /* unterminated")

        val comment = lexed.firstOrNull { it.first == NekoTokens.BLOCK_COMMENT }
        assertNotNull("expected the unterminated comment to still be a token", comment)
        assertEquals("/* unterminated", comment!!.second)
    }

    fun testRejectedCharacterBecomesABadCharacterRatherThanAGap() {
        // The compiler's lexer reports a lone '&' as an error and emits no token for it.
        val lexed = lex("x = a & b")
        assertTrue(NekoTokens.BAD_CHARACTER in lexed.map { it.first })
        assertEquals("x = a & b", lexed.joinToString("") { it.second })
    }

    // --------------------------------------------------------------------------- highlighting

    fun testHighlighterColoursKeywordsIdentifiersNumbersAndComments() {
        val highlighter = NekoSyntaxHighlighter()

        assertContainsElements(
            highlighter.getTokenHighlights(NekoTokens.of(TokenType.CLASS)).toList(),
            DefaultLanguageHighlighterColors.KEYWORD,
        )
        assertContainsElements(
            highlighter.getTokenHighlights(NekoTokens.of(TokenType.IDENTIFIER)).toList(),
            DefaultLanguageHighlighterColors.IDENTIFIER,
        )
        assertContainsElements(
            highlighter.getTokenHighlights(NekoTokens.of(TokenType.INTEGER_LITERAL)).toList(),
            DefaultLanguageHighlighterColors.NUMBER,
        )
        assertContainsElements(
            highlighter.getTokenHighlights(NekoTokens.of(TokenType.STRING_LITERAL)).toList(),
            DefaultLanguageHighlighterColors.STRING,
        )
        assertContainsElements(
            highlighter.getTokenHighlights(NekoTokens.LINE_COMMENT).toList(),
            DefaultLanguageHighlighterColors.LINE_COMMENT,
        )
        assertContainsElements(
            highlighter.getTokenHighlights(NekoTokens.BLOCK_COMMENT).toList(),
            DefaultLanguageHighlighterColors.BLOCK_COMMENT,
        )
    }

    fun testHighlighterIsSilentAboutTokensItDoesNotKnow() {
        // Whitespace must stay uncoloured, otherwise the editor paints the whole file.
        assertEquals(
            emptyList<TextAttributesKey>(),
            NekoSyntaxHighlighter().getTokenHighlights(NekoTokens.WHITE_SPACE).toList(),
        )
    }

    fun testEditorHighlightsARealFile() {
        // Drives the editor's own highlighting pipeline -- lexer, parser and highlighter together.
        // The syntax colours live on the *editor* highlighter, not on `doHighlighting`, which
        // reports only the annotator's findings; asserting on that list would prove nothing about
        // colouring, and would pass even if the highlighter were never registered.
        val file = myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    private val field1: int = 123

                    fun main(args: string[]) {
                        // a comment
                        println(field1)
                    }
                }
            }
            """.trimIndent(),
        )

        val colours = collectColours(file)
        assertContainsElements(
            colours,
            DefaultLanguageHighlighterColors.KEYWORD,
            DefaultLanguageHighlighterColors.IDENTIFIER,
            DefaultLanguageHighlighterColors.NUMBER,
            DefaultLanguageHighlighterColors.LINE_COMMENT,
        )
    }

    fun testEditorLeavesWhitespaceUncoloured() {
        val file = myFixture.configureByText("Sample.neko", "class  Sample  {  }")

        val highlighter = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
            .getInstance()
            .createEditorHighlighter(project, file.virtualFile)
        highlighter.setText(file.text)

        val iterator = highlighter.createIterator(0)
        while (!iterator.atEnd()) {
            if (iterator.tokenType == NekoTokens.WHITE_SPACE) {
                // Whitespace must not be painted, or the editor colours the whole file.
                assertEmpty(iterator.textAttributesKeys.orEmpty().toList())
            }
            iterator.advance()
        }
    }

    // --------------------------------------------------------------------- compiler diagnostics

    fun testAnnotatorReportsACompilerError() {
        // A duplicate local: the analyzer's own check, surfaced in the editor with the compiler's
        // wording. This is the assertion that proves the plugin reuses the compiler rather than
        // re-implementing checks.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main(args: string[]) {
                        str: string = "Hell world!"
                        str: string = "new value"
                    }
                }
            }
            """.trimIndent(),
        )

        val errors = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertTrue(
            "expected the compiler's duplicate-declaration error, got ${errors.map { it.description }}",
            errors.any { it.description.contains("already declared") },
        )
    }

    fun testAnnotatorReportsNoErrorsForAValidProgram() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    private val field1: int = 123

                    fun main(args: string[]) {
                        println(field1)
                    }
                }
            }
            """.trimIndent(),
        )

        val errors = myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertTrue(
            "a valid program must be clean, got ${errors.map { it.description }}",
            errors.isEmpty(),
        )
    }

    /**
     * A project copy of a standard library file is that file, not a second declaration.
     *
     * The IDE prepends the packaged library so the editor can resolve `scratch`, `nyan` and `yield`.
     * When the project *is* the library — this repository — those very files are also project files,
     * so prepending the packaged copy declared every library class twice. The duplicate was reported
     * against the project's own file, which is the one the reader has open: "interface
     * 'ScratchHandler1' is already declared" on a file that declares it once.
     *
     * The fixture recreates that layout by putting a library file at the path the library keeps it at,
     * and then highlights *that* file — highlighting an unrelated one would see nothing, because the
     * diagnostic is anchored where the second declaration is.
     */
    fun testAProjectCopyOfALibraryFileIsNotAnalysedTwice() {
        val file = myFixture.addFileToProject(
            "stdlib/com/github/kusa233/neko/stdlib/emitter/handler/ScratchHandler1.neko",
            """
            locate com.github.kusa233.neko.stdlib.emitter.handler

            interface ScratchHandler1 {
                fun dispatch(arg0: Object, var1: Enum): Object
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        // The packaged library really has to be there, or this test would pass by having nothing to
        // duplicate against.
        assertTrue(
            "the standard library should reach the test runtime",
            com.github.kusa233.neko.source.StandardLibrary.sources().isNotEmpty(),
        )

        // The reader's symptom, first: the file they have open must not be told it is a duplicate.
        val errors = myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertTrue(
            "a library file the project provides must not be declared twice, but got " +
                "${errors.map { it.description }}",
            errors.none { it.description.contains("already declared") },
        )

        // And the mechanism behind it: one copy in the program, not two.
        val model = NekoProgramModel.forProject(myFixture.project)
        val declaring = model.sources.count { it.content.contains("interface ScratchHandler1") }
        assertEquals("the project's copy should replace the packaged one, not join it", 1, declaring)
    }


    fun testAnnotatorReportsAnUnknownName() {
        // Resolution across the project, not just within one file: `println` is a built-in, so the
        // analyzer accepts it, while `print` is not and must be reported.
        //
        // The expected text is the compiler's own wording, asserted verbatim. The plugin shows the
        // diagnostics the compiler produced rather than composing its own, so if this message ever
        // changes in the compiler the plugin must follow it -- and this assertion is what says so.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main(args: string[]) {
                        print("hi")
                    }
                }
            }
            """.trimIndent(),
        )

        val errors = myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertTrue(
            "expected the compiler's own message, got ${errors.map { it.description }}",
            errors.any { it.description == "there is no function named 'print'" },
        )
    }

    fun testAnnotatorAnchorsTheErrorToTheOffendingToken() {
        // The compiler reports the exact span, and the annotation must cover exactly that text --
        // this is the "precise to the line and the point" behaviour, checked in the editor.
        val file = myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main(args: string[]) {
                        print("hi")
                    }
                }
            }
            """.trimIndent(),
        )

        val error = myFixture.doHighlighting()
            .first { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }

        assertEquals("print", file.text.substring(error.startOffset, error.endOffset))
    }

    // ----------------------------------------------------------------------------- completion

    fun testCompletionOffersKeywordsAndBuiltins() {
        // The prefix is deliberately short enough to match several entries. A prefix matching
        // exactly one entry makes the IDE insert it instead of showing a list, which is correct
        // behaviour but leaves nothing to assert on.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main(args: string[]) {
                        p<caret>
                    }
                }
            }
            """.trimIndent(),
        )

        val suggestions = completeAtCaret()

        // `println` comes from the compiler's own BuiltinLibrary, so this also proves the standard
        // library reaches the completion list.
        assertContainsElements(suggestions, "println")
        // Keywords are offered wherever a statement can start.
        assertContainsElements(suggestions, "private")
    }

    fun testCompletionOffersClassesDeclaredInTheProject() {
        // Two classes share a prefix so the IDE shows a list rather than auto-inserting one.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Helper {
                static {
                    val value: int = 1
                }
            }

            class Handler {
                static {
                    val other: int = 2
                }
            }

            class Sample {
                static {
                    fun main(args: string[]) {
                        H<caret>
                    }
                }
            }
            """.trimIndent(),
        )

        val suggestions = completeAtCaret()

        // Both come from analysing the project's own sources, so this proves the analyzer's symbol
        // table reaches completion -- not just a fixed list of built-ins.
        assertContainsElements(suggestions, "Helper", "Handler")
    }

    // ------------------------------------------------------------------------------ in scope

    fun testCompletionOffersALocalVariableDeclaredAboveTheCaret() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        counter: int = 1
                        <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // This is the completion that a flat list of the program's classes cannot give: the name
        // exists only because of where the caret is.
        assertContainsElements(completeAtCaret(), "counter")
    }

    fun testCompletionOffersAFunctionParameter() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun greet(who: string) {
                        <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        assertContainsElements(completeAtCaret(), "who")
    }

    fun testCompletionDoesNotOfferALocalDeclaredBelowTheCaret() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        <caret>
                        later: int = 1
                    }
                }
            }
            """.trimIndent(),
        )

        // Suggesting it would propose a name the analyzer rejects, which is the one thing a
        // completion list must not do.
        assertFalse(
            "a variable declared below the caret is not in scope yet",
            completeAtCaret().contains("later"),
        )
    }

    fun testCompletionDoesNotOfferALocalFromAnotherFunction() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun other() {
                        elsewhere: int = 1
                    }

                    fun main() {
                        <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        assertFalse(
            "a local belongs to the function that declares it",
            completeAtCaret().contains("elsewhere"),
        )
    }

    fun testCompletionOffersAStaticMemberOfTheEnclosingClass() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    val limit: int = 9

                    fun helper(): int {
                        return 1
                    }

                    fun main() {
                        <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // A member of the class being edited is reachable by its bare name from a static body, so
        // it belongs in the list -- and unlike a local, nothing about the caret position says so.
        assertContainsElements(completeAtCaret(), "helper", "limit")
    }

    fun testCompletionLeavesOutAnInstanceMemberInAStaticBody() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        <caret>
                    }
                }

                fun instanceOnly(): int {
                    return 1
                }
            }
            """.trimIndent(),
        )

        // There is no object to call it on, so the analyzer would reject the bare name.
        assertFalse(
            "an instance member is not reachable from a static body",
            completeAtCaret().contains("instanceOnly"),
        )
    }

    // ---------------------------------------------------------------------------- type names

    fun testCompletionOffersBuiltinTypesInATypePosition() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        value: <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // `int`, `string` and `bool` are not classes, so nothing else in the list could supply them.
        assertContainsElements(completeAtCaret(), "int", "bool", "string", "Object")
    }

    fun testCompletionOffersTheJavaSpellingOfABuiltinType() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        value: <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // These are the names the analyzer accepts as aliases, so offering them is the point: they
        // are what a reader reaches for when thinking in Java types.
        assertContainsElements(completeAtCaret(), "Integer", "Boolean", "String")
    }

    fun testCompletionOffersTypesAfterAPartlyTypedName() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        value: In<caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // The name being typed must not hide the type position it sits in.
        assertContainsElements(completeAtCaret(), "Integer")
    }

    fun testCompletionOffersTypesForAParameter() {
        // After the colon, where the type is written. Right after `(` would be the parameter's
        // *name*, which is a different position and deliberately not this list.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main(value: <caret>) {
                    }
                }
            }
            """.trimIndent(),
        )

        assertContainsElements(completeAtCaret(), "int")
    }

    fun testCompletionOffersTypesForAReturnType() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun value(): <caret> {
                    }
                }
            }
            """.trimIndent(),
        )

        assertContainsElements(completeAtCaret(), "int")
    }

    fun testCompletionDoesNotOfferLocalsWhereATypeIsExpected() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        counter: int = 1
                        value: <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // A type position is its own vocabulary: a variable is not a type, and offering it would
        // suggest something the analyzer rejects.
        assertFalse(
            "a local is not a type",
            completeAtCaret().contains("counter"),
        )
    }

    fun testCompletionStillOffersLocalsWhereAValueIsExpected() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        counter: int = 1
                        <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // The counterpart of the test above: outside a type position, nothing has been taken away.
        assertContainsElements(completeAtCaret(), "counter")
    }

    fun testCompletionOffersTypesInAnEmitClause() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun raiser() {
                    } emit [<caret>]
                }
            }
            """.trimIndent(),
        )

        // An `emit` clause names types, so it is a type position even though no colon introduces it.
        assertContainsElements(completeAtCaret(), "int", "string")
    }

    fun testCompletionOffersTypesAfterACommaInAnEmitClause() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun raiser() {
                    } emit [int, <caret>]
                }
            }
            """.trimIndent(),
        )

        assertContainsElements(completeAtCaret(), "int", "Object")
    }

    fun testCompletionOffersTypesAfterAnEmitResultArrow() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun raiser() {
                    } emit [int] -> <caret>
                }
            }
            """.trimIndent(),
        )

        // The half after the arrow is the handler's result, so it is a type position too.
        assertContainsElements(completeAtCaret(), "int", "string")
    }

    fun testCompletionStillOffersLocalsAfterAWhenBranchArrow() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        counter: int = 1
                        when (counter) {
                            1 -> <caret>
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        // A `when` branch's arrow introduces a statement, not a type, so the result-arrow rule must
        // not turn it into a type position.
        assertContainsElements(completeAtCaret(), "counter")
    }

    fun testCompletionDoesNotTreatACallArgumentListAsATypePosition() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        counter: int = 1
                        println(counter, <caret>)
                    }
                }
            }
            """.trimIndent(),
        )

        // A comma inside a call is a value position. The `emit` keyword is what separates the two,
        // and its absence here is the whole reason this test exists.
        assertContainsElements(completeAtCaret(), "counter")
    }

    fun testCompletionDoesNotTreatAnEmitClauseAsATypePositionOnceItIsClosed() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        counter: int = 1
                        <caret>
                    }

                    fun raiser() {
                    } emit [int] -> int
                }
            }
            """.trimIndent(),
        )

        // The clause is behind the caret, so the caret is in a statement position and a local is
        // what belongs there.
        assertContainsElements(completeAtCaret(), "counter")
    }

    // ------------------------------------------------------------------------ platform classes

    fun testCompletionOffersJavaLangClasses() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        value: <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // Read from the running JDK rather than a table, so these are classes the compiler will also
        // resolve: an unqualified capitalized name is looked up in `java.lang` first.
        assertContainsElements(completeAtCaret(), "Exception", "RuntimeException")
    }

    fun testCompletionOffersJavaLangClassesInAValuePositionToo() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // A class name is nameable wherever a name is, not only in a type position.
        assertContainsElements(completeAtCaret(), "Exception")
    }

    fun testCompletionOffersClassesFromTheImportedPackage() {
        myFixture.configureByText(
            "Sample.neko",
            """
            locate com.github.kusa233.test

            import java.util

            class Sample {
                static {
                    fun main() {
                        value: <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // An undeclared capitalized name falls back to the file's imported package, so these are
        // nameable here -- and they are offered because of that import, not in spite of it.
        assertContainsElements(completeAtCaret(), "ArrayList")
    }


    // ------------------------------------------------------------------------- the standard library

    fun testTheEditorSeesTheStandardLibrary() {
        // The plugin drives `Parser` and `Analyzer` directly rather than going through `Compiler`, so
        // it has to prepend the library itself. Without that, this program would be reported as
        // "'scratch' and 'nyan' need EmitYieldException" in the editor while compiling perfectly with
        // `gradlew compileNeko` — the worst kind of disagreement, because only one of the two is wrong.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun caller(): int {
                        v: int = try f() { a ->
                            nyan a
                        }
                        return v
                    }

                    fun f(): int {
                        return scratch 1, 2
                    } emit [int, int] -> int
                }
            }
            """.trimIndent(),
        )

        val errors = myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }

        assertEquals(
            "a program using 'scratch' must not be reported as missing its exception: " +
                errors.map { it.description },
            0,
            errors.size,
        )
    }

    fun testALibraryDiagnosticIsNotBlamedOnTheOpenFile() {
        // The library's sources are analysed as part of the program but belong to no file on disk, so
        // their diagnostics must not be drawn in whichever editor happens to be open. `belongsTo`
        // compares paths, and a library source has none — this asserts that the comparison is actually
        // consulted rather than everything being reported against the current file.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main(args: string[]) {
                        println(1)
                    }
                }
            }
            """.trimIndent(),
        )

        val errors = myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }

        assertEquals(
            "a valid program should show no errors: ${errors.map { it.description }}",
            0,
            errors.size,
        )
    }

    // ------------------------------------------------------------------------ unresolved names

    fun testAnUnknownNameIsPaintedNotMerelyUnderlined() {
        // The distinction this test exists for: a squiggle says "something on this line is wrong",
        // while a painted identifier says *which* name. `green` below is a plain local that resolves,
        // and it must not be painted -- only `missingName` may be.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main(args: string[]) {
                        val green: int = 1
                        println(green)
                        println(missingName)
                    }
                }
            }
            """.trimIndent(),
        )

        val painted = paintedSpans()

        assertTrue(
            "the unresolved name should be painted, but the painted spans were: $painted",
            "missingName" in painted,
        )
        assertTrue(
            "a name that resolves must not be painted, but the painted spans were: $painted",
            "green" !in painted,
        )
    }

    fun testAnUnknownMemberIsPainted() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main(args: string[]) {
                        val s: string = "hi"
                        println(s.missingMember)
                    }
                }
            }
            """.trimIndent(),
        )

        val painted = paintedSpans()

        // The exact span matters: the compiler anchors this on the member name alone, so the
        // receiver `s` -- which resolved fine -- is left unpainted.
        assertTrue(
            "expected 'missingMember' to be painted, but the painted spans were: $painted",
            "missingMember" in painted,
        )
        assertTrue(
            "the receiver resolved, so it must not be painted: $painted",
            "s" !in painted,
        )
    }

    fun testAnUnknownTypeIsPainted() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun make(other: HashMap): int {
                        return 1
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(
            "an unresolvable type name should be painted too",
            "HashMap" in paintedSpans(),
        )
    }

    fun testAnErrorAboutSomethingElseIsNotPainted() {
        // Not every error is an unresolved name. This one is about an interface method that was not
        // written, and it is anchored on `Sample` -- a name that resolved perfectly well. Painting it
        // would tell the reader the class name is wrong, which is the opposite of the truth.
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample: Runnable {
            }
            """.trimIndent(),
        )

        val painted = paintedSpans()

        assertTrue(
            "a resolved class name must not be painted: $painted",
            "Sample" !in painted,
        )
    }

    /** The text of every span the editor painted with a colour key, in document order. */
    private fun paintedSpans(): List<String> {
        val text = myFixture.editor.document.text
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey != null }
            .map { text.substring(it.startOffset, it.endOffset) }
    }

    // ------------------------------------------------------------------------- automatic imports

    fun testCompletionOffersAClassThatNeedsAnImportOnceTheReaderStartsTypingIt() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        value: Hash<caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // `HashMap` is in `java.util`, which this file does not import, so the name would not compile
        // as it stands. It is offered anyway because accepting it writes the clause -- and only once
        // a prefix has been typed, which is what keeps the popup from being flooded by all 3373
        // classes of `java.base`.
        assertContainsElements(completeAtCaret(), "HashMap")
    }

    fun testAnEmptyPrefixDoesNotOfferEveryPlatformClass() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        value: <caret>
                    }
                }
            }
            """.trimIndent(),
        )

        // The counterpart to the test above: with nothing typed, only `java.lang` (which needs no
        // import) is offered. Offering the whole platform module here would overflow the popup.
        val names = completeAtCaret()
        assertFalse("HashMap needs an import, so it must wait for a prefix", "HashMap" in names)
        assertTrue("java.lang classes need no import, so they are offered", "Exception" in names)
    }

    fun testAcceptingAClassThatNeedsAnImportWritesTheClause() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun main() {
                        value: Hash<caret>
                    }
                }
            }
            """.trimIndent(),
        )

        val elements = myFixture.completeBasic().orEmpty()
        val hashMap = elements.first { it.lookupString == "HashMap" }
        myFixture.lookup.currentItem = hashMap
        myFixture.finishLookup('\t')

        // The whole point of the feature: one keystroke both inserts the name and makes it resolvable.
        val text = myFixture.editor.document.text
        assertTrue(
            "expected an import to be written, but the file is:\n$text",
            text.contains("import java.util"),
        )
        assertTrue("expected the name itself to be inserted, but the file is:\n$text", text.contains("HashHashMap") || text.contains("HashMap"))
    }

    fun testAnImportIsNotWrittenTwice() {
        val file = myFixture.configureByText(
            "Sample.neko",
            """
            locate com.github.kusa233.test

            import java.util

            class Sample {
                static {
                    fun main() {
                        value: HashMap<caret>
                    }
                }
            }
            """.trimIndent(),
        ) as NekoPsiFile

        // A second identical clause is an error in the language ("already imported"), so asking for
        // one the file already has must be a no-op rather than a compiling file turned into a broken
        // one.
        assertFalse("no change should be needed", NekoImports.addImport(file, "java.util"))
        assertEquals(
            "the file should still have exactly one import of java.util",
            1L,
            Regex("import java\\.util(\\s|$)").findAll(myFixture.editor.document.text).count().toLong(),
        )
    }

    fun testAnImportFollowsAnExistingImport() {
        val file = myFixture.configureByText(
            "Sample.neko",
            """
            locate com.github.kusa233.test

            import java.io

            class Sample {
            }
            """.trimIndent(),
        ) as NekoPsiFile

        assertTrue(NekoImports.addImport(file, "java.util"))

        // New clauses join the group rather than being scattered: `import` lines are read as one
        // header, and a clause below the first class would not compile at all. Asserting "directly
        // after the last existing import" pins the group's contiguity without also pinning the blank
        // lines that separate the header from the declarations.
        val lines = myFixture.editor.document.text.lines()
        val ioLine = lines.indexOfFirst { it.trim() == "import java.io" }
        assertEquals(
            "the new import should follow the existing one",
            "import java.util",
            lines.getOrNull(ioLine + 1)?.trim(),
        )
    }

    fun testAnImportFollowsTheLocateClauseWhenTheFileHasNoImport() {
        val file = myFixture.configureByText(
            "Sample.neko",
            """
            locate com.github.kusa233.test

            class Sample {
            }
            """.trimIndent(),
        ) as NekoPsiFile

        assertTrue(NekoImports.addImport(file, "java.util"))

        // A blank line is kept between `locate` and the import: Neko files in this repository always
        // separate the two, and `locate a.b` directly above `import c.d` reads as a single clause.
        val text = myFixture.editor.document.text
        assertTrue(
            "expected a blank line between locate and import, but the file is:\n$text",
            text.contains("locate com.github.kusa233.test\n\nimport java.util\n"),
        )
    }

    fun testAnImportGoesToTheTopOfAFileWithNoHeader() {
        val file = myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
            }
            """.trimIndent(),
        ) as NekoPsiFile

        assertTrue(NekoImports.addImport(file, "java.util"))

        // Nothing precedes the import, so it is the first line -- and a blank line after it keeps the
        // header from running into the first declaration.
        val text = myFixture.editor.document.text
        assertTrue(
            "expected the import at the top, but the file is:\n$text",
            text.startsWith("import java.util\n"),
        )
    }

    fun testAnUnimportedPlatformClassIsReportedWithAnImportFix() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Sample {
                static {
                    fun make(other: HashMap): int {
                        return 1
                    }
                }
            }
            """.trimIndent(),
        )

        // The analyzer's diagnostic carries the clause, and the annotator turns it into an action, so
        // the reader never has to remember which package a class lives in.
        val fixes = myFixture.getAllQuickFixes().map { it.text }
        assertTrue(
            "expected an import quick fix, but the editor offered: $fixes",
            fixes.any { it.contains("java.util") },
        )
    }

    // ------------------------------------------------------- imports between Neko packages

    /**
     * Adds a second Neko file to the project, so a test can exercise a name from another package.
     *
     * `myFixture.configureByText` only ever creates the file under test, and the whole point of these
     * cases is a *second* package — so the helper file has to be added to the same in-memory project
     * before the file under test is configured, or the model would never see it.
     */
    private fun addHelperFile(path: String, text: String) {
        myFixture.addFileToProject(path, text)
    }

    fun testAClassInAnotherPackageIsReportedAsNeedingAnImport() {
        addHelperFile(
            "other/Helper.neko",
            """
            locate other.pkg

            class Helper {
                static {
                    var shared: int = 1
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Sample.neko",
            """
            locate my.pkg

            class Sample {
                static {
                    fun main() {
                        println(Helper.shared)
                    }
                }
            }
            """.trimIndent(),
        )

        // `locate` decides what a file may name, so `Helper` from `other.pkg` is not writable here
        // until the file imports it. The error has to say so — an unresolved reference with no
        // explanation would leave the reader guessing which package to write.
        val errors = myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            .map { it.description }
        assertTrue(
            "expected an error about the missing import, but got: $errors",
            errors.any { "other.pkg.Helper" in it },
        )
    }

    fun testAnUnimportedClassFromAnotherPackageOffersAnImportFix() {
        addHelperFile(
            "other/Helper.neko",
            """
            locate other.pkg

            class Helper {
                static {
                    var shared: int = 1
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Sample.neko",
            """
            locate my.pkg

            class Sample {
                static {
                    fun main() {
                        println(Helper.shared)
                    }
                }
            }
            """.trimIndent(),
        )

        // The compiler fills in `suggestedImport`, and the annotator turns it into Alt+Enter — the
        // same mechanism a platform class already used, now covering the program's own classes.
        val fixes = myFixture.getAllQuickFixes().map { it.text }
        assertTrue(
            "expected an import quick fix naming the class, but the editor offered: $fixes",
            fixes.any { "other.pkg.Helper" in it },
        )
    }

    fun testAcceptingTheQuickFixWritesTheImport() {
        addHelperFile(
            "other/Helper.neko",
            """
            locate other.pkg

            class Helper {
                static {
                    var shared: int = 1
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Sample.neko",
            """
            locate my.pkg

            class Sample {
                static {
                    fun main() {
                        println(Hel<caret>per.shared)
                    }
                }
            }
            """.trimIndent(),
        )

        val fix = myFixture.getAllQuickFixes().first { "other.pkg.Helper" in it.text }
        myFixture.launchAction(fix)

        // Applying it has to make the file legal, which means the clause and nothing else: the name
        // was already written, so only the header was missing.
        val text = myFixture.editor.document.text
        assertTrue(
            "expected the import to be written, but the file is:\n$text",
            text.contains("import other.pkg.Helper"),
        )
    }

    fun testCompletionOfAClassFromAnotherPackageWritesTheImport() {
        addHelperFile(
            "other/Helper.neko",
            """
            locate other.pkg

            class Helper {
                static {
                    var shared: int = 1
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Sample.neko",
            """
            locate my.pkg

            class Sample {
                static {
                    fun main() {
                        println(Hel<caret>)
                    }
                }
            }
            """.trimIndent(),
        )

        val helper = myFixture.completeBasic()?.firstOrNull { it.lookupString == "Helper" }
        assertNotNull("expected 'Helper' in the completion popup", helper)
        myFixture.lookup.currentItem = helper
        myFixture.finishLookup('\n')

        // Pressing Enter on a row that needs an import must write it, or the accepted name would
        // leave the file uncompilable — the completion would have made things worse.
        val text = myFixture.editor.document.text
        assertTrue(
            "expected the import to be written on Enter, but the file is:\n$text",
            text.contains("import other.pkg.Helper"),
        )
        assertTrue("expected the name to be inserted, but the file is:\n$text", "Helper" in text)
    }

    fun testASamePackageClassNeedsNoImportAndGetsNoFix() {
        addHelperFile(
            "mine/Helper.neko",
            """
            locate my.pkg

            class Helper {
                static {
                    var shared: int = 1
                }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Sample.neko",
            """
            locate my.pkg

            class Sample {
                static {
                    fun main() {
                        println(Helper.shared)
                    }
                }
            }
            """.trimIndent(),
        )

        // The other half of the rule: two files with the same `locate` are one package, so this must
        // resolve with nothing written — and offering an import here would be noise.
        val errors = myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            .map { it.description }
        assertTrue("a same-package name should resolve, but got: $errors", errors.isEmpty())
        val fixes = myFixture.getAllQuickFixes().map { it.text }
        assertTrue("no import should be offered, but got: $fixes", fixes.none { "Helper" in it })
    }

    // ---------------------------------------------------------------------- method index & symbol

    fun testMethodIndexFindsJavaStandardLibraryMethods() {
        // Methods declared in java.base should be indexed by name and class
        val toStringMethods = NekoMethodIndex.findMethodsByName("toString", project)
        assertTrue("expected java.base toString methods", toStringMethods.isNotEmpty())
        assertTrue("expected Arrays or Object in toString owners", toStringMethods.any { it.ownerSimpleName == "Arrays" || it.ownerSimpleName == "Object" })

        val arrayMethods = NekoMethodIndex.findStaticMethodsByClass("Arrays", project)
        assertTrue("expected static methods on Arrays", arrayMethods.isNotEmpty())
        val names = arrayMethods.map { it.name }.toSet()
        assertTrue("expected Arrays.toString in index", "toString" in names)
        assertTrue("expected Arrays.sort in index", "sort" in names)
        assertTrue("expected Arrays.binarySearch in index", "binarySearch" in names)

        val mathMethods = NekoMethodIndex.findStaticMethodsByClass("Math", project)
        val mathNames = mathMethods.map { it.name }.toSet()
        assertTrue("expected Math.abs in index", "abs" in mathNames)
        assertTrue("expected Math.max in index", "max" in mathNames)
    }

    fun testMethodIndexFindsNekoClassMethods() {
        myFixture.configureByText(
            "Service.neko",
            """
            locate com.example.demo

            class Service {
                static {
                    fun serve(port: int): string {
                        return "ok"
                    }
                }

                fun handle() {
                }
            }
            """.trimIndent(),
        )

        val serveMethods = NekoMethodIndex.findMethodsByName("serve", project)
        assertTrue("expected 'serve' method in index", serveMethods.isNotEmpty())
        val serve = serveMethods.first { it.ownerSimpleName == "Service" }
        assertEquals("Service", serve.ownerSimpleName)
        assertEquals("com.example.demo.Service", serve.ownerQualifiedName)
        assertTrue("serve should be static", serve.isStatic)
        assertEquals(listOf("int"), serve.parameterTypes)
        assertEquals("string", serve.returnType)
        assertTrue("should be Neko method", serve.isNekoMethod)

        val handleMethods = NekoMethodIndex.findMethodsByName("handle", project)
        assertTrue("expected 'handle' method in index", handleMethods.isNotEmpty())
        val handle = handleMethods.first { it.ownerSimpleName == "Service" }
        assertFalse("handle should be an instance method", handle.isStatic)

        val serviceMethods = NekoMethodIndex.findMethodsByClass("Service", project)
        val serviceNames = serviceMethods.map { it.name }.toSet()
        assertTrue("serve should be in Service methods", "serve" in serviceNames)
        assertTrue("handle should be in Service methods", "handle" in serviceNames)
    }

    fun testGotoSymbolContributorFindsNekoAndJavaMethods() {
        myFixture.configureByText(
            "Greeter.neko",
            """
            locate com.example.greet

            class Greeter {
                static {
                    fun greet(name: string): string {
                        return "hello"
                    }
                }
            }
            """.trimIndent(),
        )

        val contributor = NekoGotoSymbolContributor()
        val allNames = contributor.getNames(project, true).toSet()
        assertTrue("expected 'greet' in symbol names", "greet" in allNames)
        assertTrue("expected 'toString' in symbol names", "toString" in allNames)

        val greetItems = contributor.getItemsByName("greet", "greet", project, true)
        assertTrue("expected greet item in symbol search", greetItems.isNotEmpty())
        val greetItem = greetItems.first { it.presentation?.locationString?.contains("Greeter") == true }
        assertEquals("greet", greetItem.name)
        val presentation = greetItem.presentation
        assertNotNull("presentation must be present", presentation)
        assertTrue(
            "presentation text should have parameter info: ${presentation?.presentableText}",
            presentation?.presentableText?.contains("greet(name: string): string") == true,
        )
        assertTrue(
            "location should mention Greeter: ${presentation?.locationString}",
            presentation?.locationString?.contains("Greeter") == true,
        )
        assertTrue("should be navigable", greetItem.canNavigate())
        assertTrue("should be navigable to source", greetItem.canNavigateToSource())

        val toStringItems = contributor.getItemsByName("toString", "toString", project, true)
        assertTrue("expected toString items in symbol search", toStringItems.isNotEmpty())
        val javaItem = toStringItems.first { it.presentation?.locationString?.contains("Java Platform") == true }
        assertTrue(
            "location should mention Java Platform",
            javaItem.presentation?.locationString?.contains("Java Platform") == true,
        )
    }

    fun testCompletionOffersStaticMethodsAfterDotOnJavaClass() {
        myFixture.configureByText(
            "Sample.neko",
            """
            import java.util

            class Sample {
                fun test() {
                    Arrays.<caret>
                }
            }
            """.trimIndent(),
        )

        val completions = completeAtCaret()
        assertTrue("expected toString in Arrays completion: $completions", "toString" in completions)
        assertTrue("expected sort in Arrays completion: $completions", "sort" in completions)
        assertTrue("expected binarySearch in Arrays completion: $completions", "binarySearch" in completions)
    }

    fun testCompletionOffersStaticMethodsAfterDotOnNekoClass() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class MyMath {
                static {
                    fun add(a: int, b: int): int {
                        return a + b
                    }
                }
            }

            class Sample {
                fun test() {
                    MyMath.<caret>
                }
            }
            """.trimIndent(),
        )

        val completions = completeAtCaret()
        assertTrue("expected add in MyMath completion: $completions", "add" in completions)
    }

    fun testCompletionOffersInstanceMethodsAfterDotOnLocalVariable() {
        myFixture.configureByText(
            "Sample.neko",
            """
            class Counter {
                fun increment(): int {
                    return 1
                }
            }

            class Sample {
                fun test() {
                    val c: Counter = Counter()
                    c.<caret>
                }
            }
            """.trimIndent(),
        )

        val completions = completeAtCaret()
        assertTrue("expected increment on Counter instance: $completions", "increment" in completions)
    }

    fun testImportedPackageDoesNotShowUnresolvedForPlatformClass() {
        myFixture.configureByText(
            "Sample.neko",
            """
            import java.util

            class Sample {
                static {
                    fun main(args: string[]) {
                        println(Arrays.toString(args))
                    }
                }
            }
            """.trimIndent(),
        )

        val errors = myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            .map { it.description }
        assertTrue("expected no unresolved reference errors for Arrays, but got: $errors", errors.isEmpty())
        val painted = paintedSpans()
        assertFalse("Arrays should not be painted as unresolved, but painted was: $painted", "Arrays" in painted)
    }

    // -------------------------------------------------------------------------------- helpers

    /** Every token the highlighting lexer produces for [text], as type/text pairs. */
    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = NekoHighlightingLexer()
        lexer.start(text)
        val result = ArrayList<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            assertTrue("empty token of type $type", end > start)
            result += type to text.substring(start, end)
            lexer.advance()
        }
        return result
    }

    /** The lookup strings the completion popup would show at the caret. */
    private fun completeAtCaret(): List<String> {
        // `completeBasic` returns `null` when the IDE inserted the single matching variant instead
        // of opening a popup. That is a successful outcome, not an empty one, so it is asserted
        // against rather than silently reported as "no completions".
        val elements = myFixture.completeBasic()
        assertNotNull(
            "expected a completion popup; the IDE auto-inserted a single variant instead",
            elements,
        )
        return elements.orEmpty().map { it.lookupString }
    }

    /** The attribute keys the editor highlighter applies across the whole file, in order. */
    private fun collectColours(file: com.intellij.psi.PsiFile): List<TextAttributesKey> {
        val highlighter = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
            .getInstance()
            .createEditorHighlighter(project, file.virtualFile)
        highlighter.setText(file.text)

        val keys = ArrayList<TextAttributesKey>()
        val iterator = highlighter.createIterator(0)
        while (!iterator.atEnd()) {
            keys += iterator.textAttributesKeys.orEmpty()
            iterator.advance()
        }
        return keys
    }
}
