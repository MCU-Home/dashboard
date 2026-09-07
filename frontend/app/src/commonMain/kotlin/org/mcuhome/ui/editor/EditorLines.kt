// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity as ApiSeverity

/**
 * Where every line of a document begins, as offsets into its text.
 *
 * The gutter, the current-line band and the diagnostic underlines all
 * work in document lines while the text field lays out visual lines, so
 * this table is what translates between the two. A document always has a
 * first line, even when it is empty.
 */
fun lineStartOffsets(text: String): List<Int> {
    val starts = mutableListOf(0)
    text.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
    return starts
}

/** The zero-based line an offset falls on, or null when there are no lines. */
fun lineIndexOf(lineStarts: List<Int>, offset: Int): Int? {
    if (lineStarts.isEmpty()) return null
    val index = lineStarts.indexOfLast { it <= offset }
    return index.takeIf { it >= 0 }
}

/**
 * The stretch of a line a diagnostic is about: from the first character
 * that is not indentation to the end of the line. Null when the line does
 * not exist or holds nothing but spaces — there is nothing to underline
 * then.
 */
fun contentRangeOfLine(text: String, oneBasedLine: Int): IntRange? {
    val lineStarts = lineStartOffsets(text)
    val start = lineStarts.getOrNull(oneBasedLine - 1) ?: return null
    val end = lineStarts.getOrNull(oneBasedLine)?.minus(1) ?: text.length
    var first = start
    while (first < end && text[first] == ' ') first++
    return if (first < end) first until end else null
}

/**
 * The diagnostics of a validation report, as the editor draws them.
 *
 * A diagnostic without a line has no place in the gutter — a missing
 * secrets file is about the project, not about a character — so it is
 * left to the lists that show every finding, and dropped here.
 */
fun editorDiagnostics(diagnostics: List<Diagnostic>): List<EditorDiagnostic> = diagnostics.mapNotNull { diagnostic ->
    val line = diagnostic.line ?: return@mapNotNull null
    EditorDiagnostic(
        line = line,
        message = diagnostic.message,
        severity = when (diagnostic.severity) {
            ApiSeverity.Error -> DiagnosticSeverity.Error
            ApiSeverity.Warning -> DiagnosticSeverity.Warning
            ApiSeverity.Info -> DiagnosticSeverity.Info
        },
    )
}
