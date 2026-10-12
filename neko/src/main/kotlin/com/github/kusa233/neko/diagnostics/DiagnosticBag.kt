package com.github.kusa233.neko.diagnostics

import com.github.kusa233.neko.source.Span

/**
 * Accumulates diagnostics produced by a compilation pass.
 *
 * Every pass (lexer, parser, analyzer, generator) reports into the same bag so that a single run
 * surfaces as many independent problems as possible instead of stopping at the first one.
 */
class DiagnosticBag {
    private val items = mutableListOf<Diagnostic>()

    val diagnostics: List<Diagnostic> get() = items

    /** `true` when at least one [Severity.ERROR] has been reported. */
    val hasErrors: Boolean get() = items.any { it.severity == Severity.ERROR }

    val errorCount: Int get() = items.count { it.severity == Severity.ERROR }

    fun report(diagnostic: Diagnostic) {
        items += diagnostic
    }

    /**
     * Reports an error anchored at [span].
     *
     * When a pass has no better anchor it passes `null`; the renderer then omits the source snippet
     * rather than pointing at a misleading location.
     */
    fun error(message: String, span: Span? = null, notes: List<Diagnostic> = emptyList()) {
        report(Diagnostic(Severity.ERROR, message, span, notes))
    }

    fun warning(message: String, span: Span? = null, notes: List<Diagnostic> = emptyList()) {
        report(Diagnostic(Severity.WARNING, message, span, notes))
    }

    /** Builds a secondary location to attach to a primary diagnostic. */
    fun note(message: String, span: Span?): Diagnostic = Diagnostic(Severity.NOTE, message, span)

    fun addAll(other: DiagnosticBag) {
        items += other.items
    }
}
