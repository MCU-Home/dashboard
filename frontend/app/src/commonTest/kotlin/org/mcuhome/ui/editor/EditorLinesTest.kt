// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import org.mcuhome.ui.api.Diagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mcuhome.ui.api.DiagnosticSeverity as ApiSeverity

class EditorLinesTest {
    private val document = "device:\n  name: garage-door\n\n    relay:\n"

    @Test
    fun every_line_start_is_found_including_the_empty_one() {
        assertEquals(listOf(0, 8, 28, 29, 40), lineStartOffsets(document))
    }

    @Test
    fun an_empty_document_still_has_one_line() {
        assertEquals(listOf(0), lineStartOffsets(""))
    }

    @Test
    fun an_offset_is_mapped_to_the_line_it_stands_on() {
        val starts = lineStartOffsets(document)
        assertEquals(0, lineIndexOf(starts, 0))
        assertEquals(0, lineIndexOf(starts, 7))
        assertEquals(1, lineIndexOf(starts, 8))
        assertEquals(1, lineIndexOf(starts, 25))
        assertEquals(2, lineIndexOf(starts, 28))
    }

    @Test
    fun the_underlined_range_of_a_line_skips_its_indentation() {
        assertEquals(10 until 27, contentRangeOfLine(document, 2))
        assertEquals(33 until 39, contentRangeOfLine(document, 4))
    }

    @Test
    fun a_line_that_is_empty_or_absent_has_nothing_to_underline() {
        assertNull(contentRangeOfLine(document, 3))
        assertNull(contentRangeOfLine(document, 9))
        assertNull(contentRangeOfLine("   ", 1))
    }

    @Test
    fun diagnostics_without_a_line_are_left_to_the_lists_that_show_everything() {
        val mapped = editorDiagnostics(
            listOf(
                Diagnostic(ApiSeverity.Error, "no board", line = 2),
                Diagnostic(ApiSeverity.Warning, "no pin", line = 4),
                Diagnostic(ApiSeverity.Info, "about the project", line = null),
            ),
        )
        assertEquals(2, mapped.size)
        assertEquals(EditorDiagnostic(2, "no board", DiagnosticSeverity.Error), mapped[0])
        assertEquals(EditorDiagnostic(4, "no pin", DiagnosticSeverity.Warning), mapped[1])
    }

    @Test
    fun the_severities_are_carried_over_one_for_one() {
        val info = editorDiagnostics(listOf(Diagnostic(ApiSeverity.Info, "note", line = 1)))
        assertEquals(DiagnosticSeverity.Info, info.single().severity)
    }
}

class EditorOverscrollTest {
    @Test
    fun theEditorKeepsBlankLinesBelowTheLastOne() {
        assertEquals(8, EDITOR_OVERSCROLL_LINES)
        assertEquals("\n".repeat(EDITOR_OVERSCROLL_LINES), overscrollText())
    }

    @Test
    fun theBlankLinesAreNothingButLineBreaks() {
        assertEquals(3, overscrollText(3).length)
        assertTrue(overscrollText(3).all { it == '\n' })
    }

    @Test
    fun anEditorWithoutOverscrollAppendsNothing() {
        assertEquals("", overscrollText(0))
        assertEquals("", overscrollText(-4))
    }

    @Test
    fun theBlankLinesAreNotPartOfTheDocument() {
        val document = "a:\n  b: 1"
        assertEquals(listOf(0, 3), lineStartOffsets(document))
        assertEquals(2, lineStartOffsets(document).size)
    }
}
