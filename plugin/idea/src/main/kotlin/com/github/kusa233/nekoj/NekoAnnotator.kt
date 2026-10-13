package com.github.kusa233.nekoj

import com.github.kusa233.neko.diagnostics.Diagnostic
import com.github.kusa233.neko.diagnostics.Severity
import com.github.kusa233.neko.source.Span
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.nio.file.Path

/**
 * Reports the compiler's own diagnostics as editor annotations.
 *
 * Nothing here re-implements a check. The plugin compiles the project with the real compiler and
 * shows what it said, so the squiggles in the editor and the errors from `./gradlew compileNeko`
 * are the same findings, worded the same way. That also means the errors improve whenever the
 * compiler does.
 *
 * The messages are the compiler's, verbatim, including the guidance in them — the diagnostics are
 * written to tell an author what to write instead, and rewording them here would throw that away.
 */
class NekoAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        if (file !is NekoPsiFile) return

        val model = modelFor(file, holder) ?: return
        val path = file.virtualFile?.path?.let { Path.of(it) }
        val textLength = file.textLength

        for (diagnostic in model.diagnostics) {
            val span = diagnostic.span ?: continue
            if (!span.belongsTo(path)) continue
            report(diagnostic, span, textLength, holder)
        }
    }

    /**
     * Gets the program model, computing it at most once per annotation pass.
     *
     * Annotators are called for every element in the file, so without this the whole project would
     * be recompiled once per element. The [com.intellij.lang.annotation.AnnotationSession] lives
     * for exactly one pass, which makes it the right place to remember the answer.
     */
    private fun modelFor(file: NekoPsiFile, holder: AnnotationHolder): NekoProgramModel? {
        val session = holder.currentAnnotationSession
        session.getUserData(MODEL_KEY)?.let { return it }
        val model = try {
            NekoProgramModel.forProject(file.project)
        } catch (error: RuntimeException) {
            return null
        }
        session.putUserData(MODEL_KEY, model)
        return model
    }

    private fun report(
        diagnostic: Diagnostic,
        span: Span,
        textLength: Int,
        holder: AnnotationHolder,
    ) {
        val range = span.toTextRange(textLength) ?: return
        val severity = when (diagnostic.severity) {
            Severity.ERROR -> HighlightSeverity.ERROR
            Severity.WARNING -> HighlightSeverity.WARNING
            Severity.NOTE -> HighlightSeverity.INFORMATION
        }

        // Notes carry the extra detail the compiler attached — a hint at the declaration, say — and
        // each becomes its own annotation when it has a location, so the reader can jump to it.
        holder.newAnnotation(severity, diagnostic.message).range(range).create()

        for (note in diagnostic.notes) {
            val noteSpan = note.span
            if (noteSpan != null && noteSpan.belongsTo(span.file.path)) {
                val noteRange = noteSpan.toTextRange(textLength) ?: continue
                holder.newAnnotation(
                    HighlightSeverity.INFORMATION,
                    note.message,
                ).range(noteRange).create()
            }
        }
    }

    private companion object {
        val MODEL_KEY: Key<NekoProgramModel> = Key.create("nekoj.program.model")
    }
}

/** Whether this span was produced for the file at [path]. */
private fun Span.belongsTo(path: Path?): Boolean {
    if (path == null) return false
    return file.path == path
}

/**
 * Converts a span to an editor range, clamped to [textLength].
 *
 * A diagnostic with no width is widened by one character so that it is actually visible: the
 * compiler anchors "unexpected end of file" at the very end of the file, where a zero-width range
 * would draw nothing at all and the reader would see no error.
 */
private fun Span.toTextRange(textLength: Int): TextRange? {
    if (textLength <= 0) return null
    var start = start.coerceIn(0, textLength)
    var end = end.coerceIn(0, textLength)
    if (start == end) {
        if (start > 0) start-- else if (end < textLength) end++
    }
    if (start >= end) return null
    return TextRange(start, end)
}
