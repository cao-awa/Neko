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
                private static val field1: int = 123

                static fun main(args: string[]) {
                    // a comment
                    println(field1)
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
                static fun main(args: string[]) {
                    str: string = "Hell world!"
                    str: string = "new value"
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
                private static val field1: int = 123

                static fun main(args: string[]) {
                    println(field1)
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
                static fun main(args: string[]) {
                    print("hi")
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
                static fun main(args: string[]) {
                    print("hi")
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
                static fun main(args: string[]) {
                    p<caret>
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
                static val value: int = 1
            }

            class Handler {
                static val other: int = 2
            }

            class Sample {
                static fun main(args: string[]) {
                    H<caret>
                }
            }
            """.trimIndent(),
        )

        val suggestions = completeAtCaret()

        // Both come from analysing the project's own sources, so this proves the analyzer's symbol
        // table reaches completion -- not just a fixed list of built-ins.
        assertContainsElements(suggestions, "Helper", "Handler")
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
