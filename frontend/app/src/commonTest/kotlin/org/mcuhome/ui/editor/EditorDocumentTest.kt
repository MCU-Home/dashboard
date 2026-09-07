// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import org.mcuhome.ui.api.SaveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorDocumentTest {
    private val loaded = EditorDocument.loaded("device:\n  name: a\n", "r1")

    @Test
    fun a_freshly_loaded_document_is_not_dirty() {
        assertFalse(loaded.dirty)
        assertFalse(loaded.canSave)
    }

    @Test
    fun typing_makes_it_dirty_and_typing_back_makes_it_clean_again() {
        val edited = loaded.edited("device:\n  name: b\n")
        assertTrue(edited.dirty)
        assertTrue(edited.canSave)
        assertFalse(edited.edited(loaded.savedText).dirty)
    }

    @Test
    fun a_save_in_flight_does_not_start_a_second_one() {
        val saving = loaded.edited("changed").saveStarted()
        assertTrue(saving.saving)
        assertFalse(saving.canSave)
    }

    @Test
    fun a_completed_save_takes_the_new_revision_and_is_clean() {
        val sent = "device:\n  name: b\n"
        val saved = loaded.edited(sent).saveStarted().saveFinished(sent, SaveResult.Saved("r2"))
        assertEquals("r2", saved.revision)
        assertEquals(sent, saved.savedText)
        assertFalse(saved.dirty)
        assertFalse(saved.saving)
    }

    @Test
    fun text_typed_while_the_save_was_in_flight_stays_unsaved() {
        val sent = "first change"
        val later = loaded.edited(sent).saveStarted().edited("second change")
        val saved = later.saveFinished(sent, SaveResult.Saved("r2"))
        assertEquals(sent, saved.savedText)
        assertEquals("second change", saved.currentText)
        assertTrue(saved.dirty)
    }

    @Test
    fun a_conflict_is_kept_with_the_other_version_and_blocks_further_writes() {
        val conflicted = conflicted()
        assertEquals(EditorConflict("r7", "someone else's text"), conflicted.conflict)
        assertTrue(conflicted.dirty)
        assertFalse(conflicted.canSave)
    }

    @Test
    fun reloading_a_conflict_takes_the_other_version_whole() {
        val reloaded = conflicted().reloaded()
        assertEquals("someone else's text", reloaded.savedText)
        assertEquals("someone else's text", reloaded.currentText)
        assertEquals("r7", reloaded.revision)
        assertNull(reloaded.conflict)
        assertFalse(reloaded.dirty)
    }

    @Test
    fun overwriting_a_conflict_keeps_the_text_and_moves_onto_the_other_revision() {
        val overwriting = conflicted().overwriting()
        assertEquals("my text", overwriting.currentText)
        assertEquals("r7", overwriting.revision)
        assertNull(overwriting.conflict)
        assertTrue(overwriting.canSave)
    }

    @Test
    fun a_failed_save_leaves_the_document_as_it_was() {
        val failed = loaded.edited("my text").saveStarted().saveFailed()
        assertFalse(failed.saving)
        assertTrue(failed.canSave)
        assertEquals(loaded.savedText, failed.savedText)
    }

    @Test
    fun resolving_a_conflict_that_is_not_there_changes_nothing() {
        assertEquals(loaded, loaded.reloaded())
        assertEquals(loaded, loaded.overwriting())
    }

    private fun conflicted(): EditorDocument = loaded
        .edited("my text")
        .saveStarted()
        .saveFinished("my text", SaveResult.Conflict("r7", "someone else's text"))
}
