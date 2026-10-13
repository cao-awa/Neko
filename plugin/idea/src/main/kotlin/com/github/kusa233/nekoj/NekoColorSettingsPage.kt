package com.github.kusa233.nekoj

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * The "Neko" page under Settings → Editor → Color Scheme.
 *
 * Every key listed here is one [NekoSyntaxHighlighter] actually produces, so the page shows the
 * reader exactly the colours their own Neko files will use, and no entry is a control that does
 * nothing.
 */
class NekoColorSettingsPage : ColorSettingsPage {

    override fun getIcon(): Icon = NekoFileType.icon

    override fun getHighlighter(): SyntaxHighlighter = NekoSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Neko"

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getDemoText(): String = DEMO_TEXT
}

private val DESCRIPTORS: Array<AttributesDescriptor> = arrayOf(
    AttributesDescriptor("Keyword", DefaultLanguageHighlighterColors.KEYWORD),
    AttributesDescriptor("Identifier", DefaultLanguageHighlighterColors.IDENTIFIER),
    AttributesDescriptor("Number", DefaultLanguageHighlighterColors.NUMBER),
    AttributesDescriptor("String", DefaultLanguageHighlighterColors.STRING),
    AttributesDescriptor("Line comment", DefaultLanguageHighlighterColors.LINE_COMMENT),
    AttributesDescriptor("Block comment", DefaultLanguageHighlighterColors.BLOCK_COMMENT),
    AttributesDescriptor("Operation sign", DefaultLanguageHighlighterColors.OPERATION_SIGN),
    AttributesDescriptor("Braces", DefaultLanguageHighlighterColors.BRACES),
    AttributesDescriptor("Parentheses", DefaultLanguageHighlighterColors.PARENTHESES),
    AttributesDescriptor("Brackets", DefaultLanguageHighlighterColors.BRACKETS),
    AttributesDescriptor("Comma", DefaultLanguageHighlighterColors.COMMA),
    AttributesDescriptor("Semicolon", DefaultLanguageHighlighterColors.SEMICOLON),
    AttributesDescriptor("Dot", DefaultLanguageHighlighterColors.DOT),
)

/** A short program that exercises every context in the scheme, using the language's own idioms. */
private val DEMO_TEXT: String = """
    locate com.github.kusa233.neko.sample

    class Sample {
        static {
            private val field1: int = 123

            fun main(args: string[]) {
                // A line comment
                /* and a
                   block comment */
                variable()
                lambda: int = lambda()(1, 2)
                if (field1 > 100) {
                    println("large")
                }
            }

            fun variable() {
                str: string = "Hell world!"
                str = "new value"
                println(str)
            }

            fun lambda(): [int, int] -> int {
                return { x, y ->
                    yield x + y
                }
            }
        }
    }
""".trimIndent()
