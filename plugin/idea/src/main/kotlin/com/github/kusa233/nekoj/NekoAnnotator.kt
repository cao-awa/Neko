package com.github.kusa233.nekoj

import com.github.kusa233.neko.diagnostics.Diagnostic
import com.github.kusa233.neko.diagnostics.Severity
import com.github.kusa233.neko.source.Span
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
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
        val annotation = holder.newAnnotation(severity, diagnostic.message).range(range)

        // A diagnostic that names the `import` to write becomes an Alt+Enter action. The clause comes
        // from the compiler rather than being re-derived here: only the analyzer knows which packages
        // the file already has and which the platform really provides, and a second implementation
        // would be free to disagree with it.
        val suggestedImport = diagnostic.suggestedImport
        if (suggestedImport != null) {
            annotation.withFix(NekoImportFix(suggestedImport))
        }
        annotation.create()

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
 * The Alt+Enter action that writes the `import` a diagnostic asked for.
 *
 * It is offered only where the compiler filled in [Diagnostic.suggestedImport], which is exactly where
 * one clause is correct: a `java.lang` class needs none, and a name two packages both declare has no
 * single right answer. This action therefore never has to decide anything — it applies the compiler's
 * answer, and reuses [NekoImports] for the placement rules.
 */
private class NekoImportFix(private val packageName: String) :
    com.intellij.codeInsight.intention.IntentionAction {

    override fun getText(): String = "Import '$packageName'"

    override fun getFamilyName(): String = "Import Neko package"

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file is NekoPsiFile && file.text.isNotEmpty()

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val target = file ?: return
        NekoImports.addImport(target, packageName)
    }
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
