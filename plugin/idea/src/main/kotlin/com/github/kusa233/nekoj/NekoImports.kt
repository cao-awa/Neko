package com.github.kusa233.nekoj

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Writes `import <package>` into a Neko file, in the one place a Neko file allows it.
 *
 * A Neko file's header is ordered, not free-form: an optional `locate`, then every `import`, then the
 * classes. Inserting the clause anywhere else is a syntax error, so the position has to be worked out
 * from the file's own text — which is what this does, rather than trusting a fixed line number or
 * appending at the end.
 *
 * The insert is done through [WriteCommandAction], so it lands on the undo stack as one step: a reader
 * who accepts an automatic import and then disagrees can undo exactly that, not the typing that
 * preceded it.
 */
object NekoImports {

    /** The keyword that introduces an import clause. */
    private const val IMPORT = "import"

    /** The keyword that introduces the package declaration. */
    private const val LOCATE = "locate"

    /**
     * Adds `import [packageName]` to [file] unless it is already there, and returns whether the file
     * was changed.
     *
     * Returning `false` for an import the file already has is what makes this safe to call from
     * completion: the reader may accept the same class twice in one session, and a second identical
     * clause is an error in the language ("already imported"), so writing it again would turn a
     * helpful action into a compilation failure.
     */
    fun addImport(file: PsiFile, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val text = file.text
        if (alreadyImported(text, packageName)) return false

        val (offset, clause) = insertion(text, packageName) ?: return false

        WriteCommandAction.runWriteCommandAction(file.project, "Import Neko Package", null, {
            val manager = PsiDocumentManager.getInstance(file.project)
            val document = manager.getDocument(file) ?: return@runWriteCommandAction
            document.insertString(offset, clause)
            manager.commitDocument(document)
        }, file)

        return true
    }

    /**
     * Whether [text] already reaches [packageName] by an `import` of its own.
     *
     * Matched on the clause as a whole rather than on a substring, so importing `java.util` is not
     * confused with `java.util.concurrent` — the two are different packages, and Neko's `import` names
     * a package rather than a prefix.
     */
    private fun alreadyImported(text: String, packageName: String): Boolean =
        headerLines(text).any { it.trim() == "$IMPORT $packageName" }

    /**
     * The offset and text of a new clause, or `null` when the file has nowhere to put one.
     *
     * Three shapes have to be handled, and they are the three a Neko file can be in:
     *
     *  * **imports already present** — the clause is appended after the last of them, keeping the
     *    group together;
     *  * **a `locate` but no imports** — the clause follows it, separated by one blank line so the
     *    header stays readable;
     *  * **neither** — the clause goes at the very top, with a blank line after it.
     *
     * The blank line matters both times: `locate a.b` directly above `import c.d` is legal, but it
     * reads as though the two were one clause, and Neko files in this repository always separate them.
     */
    private fun insertion(text: String, packageName: String): Pair<Int, String>? {
        val lines = text.split("\n")
        val clause = "$IMPORT $packageName"

        val lastImport = lines.indexOfLast { it.trim().startsWith("$IMPORT ") }
        if (lastImport >= 0) {
            return offsetOfLine(lines, lastImport + 1) to "$clause\n"
        }

        val locate = lines.indexOfFirst { it.trim().startsWith("$LOCATE ") }
        if (locate >= 0) {
            return offsetOfLine(lines, locate + 1) to "\n$clause\n"
        }

        return 0 to "$clause\n\n"
    }

    /**
     * The character offset at which line [lineIndex] begins.
     *
     * `split("\n")` keeps every line's content but drops the separators, so each line contributes its
     * own length plus the one character that ended it. An index past the end answers the end of the
     * text, which is what lets a clause be appended to a file whose last line is its last import.
     */
    private fun offsetOfLine(lines: List<String>, lineIndex: Int): Int {
        var offset = 0
        for (index in 0 until lineIndex.coerceAtMost(lines.size)) {
            offset += lines[index].length + 1
        }
        return offset
    }

    /** The lines of the file's header, i.e. everything before the first class or interface. */
    private fun headerLines(text: String): List<String> = text.split("\n").takeWhile { line ->
        val trimmed = line.trim()
        !trimmed.startsWith("class ") && !trimmed.startsWith("interface ")
    }
}
