package com.github.kusa233.nekoj

import com.github.kusa233.neko.analyzer.Analyzer
import com.github.kusa233.neko.ast.NekoFile
import com.github.kusa233.neko.ast.NekoProgram
import com.github.kusa233.neko.diagnostics.Diagnostic
import com.github.kusa233.neko.diagnostics.DiagnosticBag
import com.github.kusa233.neko.parser.Parser
import com.github.kusa233.neko.source.SourceFile
import com.github.kusa233.neko.source.StandardLibrary
import com.github.kusa233.neko.symbols.ClassSymbol
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import java.nio.file.Path

/**
 * The result of running the Neko compiler's front end over a whole project.
 *
 * Neko's unit of compilation is a *program*, not a file: a class in one file may name a class in
 * another, and `raise` needs the standard library, which is itself written in Neko. So the IDE has
 * to look at every `.neko` file at once to say anything true about any one of them. That is exactly
 * what the compiler does, and this class is the IDE's copy of the outcome.
 *
 * The ASTs and symbols are kept, rather than just the diagnostics, because completion needs to know
 * what names exist — which is the same information the analyzer had to work out to check them.
 */
class NekoProgramModel(
    /** The source text handed to the compiler, in a stable order. */
    val sources: List<SourceFile>,
    /** The parsed files, empty when parsing failed early. */
    val files: List<NekoFile>,
    /** Every class the analyzer resolved, keyed by simple name. */
    val classes: Map<String, ClassSymbol>,
    /** Everything the compiler reported, across all files. */
    val diagnostics: List<Diagnostic>,
) {

    /** The parsed form of [path]'s file, or `null` when it was never parsed. */
    fun fileOf(path: Path?): NekoFile? {
        if (path == null) return null
        val index = sources.indexOfFirst { it.path == path }
        return files.getOrNull(index)
    }

    companion object {

        /** An empty model, used when the project cannot be read — during indexing, for instance. */
        val EMPTY = NekoProgramModel(emptyList(), emptyList(), emptyMap(), emptyList())

        /**
         * The model for [project], recomputed whenever any PSI anywhere in the project changes.
         *
         * Caching on the project — rather than on the file being annotated — is deliberate. A file's
         * diagnostics depend on every other file in the program, so a change to any of them must
         * invalidate this; a cache keyed on one file would happily keep reporting that a class which
         * has since been deleted still exists.
         */
        fun forProject(project: Project): NekoProgramModel =
            CachedValuesManager.getManager(project).getCachedValue(project) {
                CachedValueProvider.Result.create(
                    compute(project),
                    PsiModificationTracker.getInstance(project),
                    ProjectRootModificationTracker.getInstance(project),
                )
            }

        private fun compute(project: Project): NekoProgramModel = try {
            val manager = PsiManager.getInstance(project)
            val sources = FileTypeIndex
                .getFiles(NekoFileType, GlobalSearchScope.projectScope(project))
                .asSequence()
                .mapNotNull { manager.findFile(it) }
                .filter { it.isValid }
                .map { it.toSourceFile() }
                // Sorted by path so a rebuild is reproducible, matching the compiler's own ordering.
                .sortedBy { it.path?.toString() ?: it.name }
                .toList()

            if (sources.isEmpty()) {
                EMPTY
            } else {
                analyse(sources)
            }
        } catch (error: RuntimeException) {
            // Reading a file that is being written, or an index that is not ready yet, must not
            // break highlighting; the next pass will try again.
            EMPTY
        }

        /**
         * Parses and analyses [sources] exactly as the compiler does, minus code generation.
         *
         * The standard library is prepended here just as [com.github.kusa233.neko.Compiler] prepends
         * it. This class drives `Parser` and `Analyzer` directly — it does not go through the compiler,
         * because it wants the symbols rather than class files — so the prepend has to be repeated.
         * Skipping it would make the editor report every `scratch`, `nyan` and `yield` in the project
         * as missing its exception, while `gradlew compileNeko` compiled the very same files cleanly.
         */
        private fun analyse(sources: List<SourceFile>): NekoProgramModel {
            val withLibrary = StandardLibrary.sources() + sources
            val diagnostics = DiagnosticBag()
            val files = withLibrary.map { Parser(it, diagnostics).parseFile() }
            if (diagnostics.hasErrors) {
                return NekoProgramModel(withLibrary, files, emptyMap(), diagnostics.diagnostics)
            }

            // The analyzer is what reports type errors AND what resolves the classes, fields and
            // functions completion offers, so one run serves both.
            val program = NekoProgram(files)
            val classes = try {
                Analyzer(program, diagnostics).analyze()
            } catch (error: RuntimeException) {
                emptyMap()
            }
            return NekoProgramModel(withLibrary, files, classes, diagnostics.diagnostics)
        }
    }
}

/**
 * Reads [this] file's current text, which is the editor's buffer rather than what is on disk.
 *
 * That distinction is the whole point: diagnostics and completion must describe the text the reader
 * is looking at, including edits they have not saved yet.
 */
private fun PsiFile.toSourceFile(): SourceFile = SourceFile(
    name = name,
    content = text,
    path = virtualFile?.path?.let { Path.of(it) },
)
