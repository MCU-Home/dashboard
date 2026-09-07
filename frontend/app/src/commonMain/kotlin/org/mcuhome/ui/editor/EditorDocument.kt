// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import org.mcuhome.ui.api.SaveResult

/**
 * What the editor knows about the file it has open: the text as it was
 * last read or written, the revision that text carries, the text on
 * screen, and — after a write that lost a race — the version that is on
 * disk now.
 *
 * It is a value with transitions rather than a set of flags scattered
 * over the screen, so "is there anything to save", "may this page be
 * left" and "what does the conflict notice offer" all have one answer
 * each, and every one of them can be checked without a browser.
 */
data class EditorDocument(
    val savedText: String,
    val revision: String,
    val currentText: String = savedText,
    val conflict: EditorConflict? = null,
    val saving: Boolean = false,
) {
    /** True while the text on screen differs from the text the server has. */
    val dirty: Boolean get() = currentText != savedText

    /** A write is worth sending when there is a change and none is in flight. */
    val canSave: Boolean get() = dirty && !saving && conflict == null

    fun edited(text: String): EditorDocument = copy(currentText = text)

    fun saveStarted(): EditorDocument = copy(saving = true)

    /**
     * The answer to a write. [sentText] is what was written, not what is
     * on screen: the user may have typed on while the request was in
     * flight, and that later text is still unsaved.
     */
    fun saveFinished(sentText: String, result: SaveResult): EditorDocument = when (result) {
        is SaveResult.Saved -> copy(savedText = sentText, revision = result.revision, saving = false, conflict = null)

        is SaveResult.Conflict -> copy(
            saving = false,
            conflict = EditorConflict(result.currentRevision, result.currentText),
        )
    }

    /** A write that failed outright leaves the document as it was. */
    fun saveFailed(): EditorDocument = copy(saving = false)

    /** Take the file as it stands on the server and drop what was typed here. */
    fun reloaded(): EditorDocument {
        val open = conflict ?: return this
        return EditorDocument(savedText = open.currentText, revision = open.currentRevision)
    }

    /**
     * Keep what was typed here and write it over the other version: the
     * document moves onto the revision the server reported, so the next
     * write is no longer stale.
     */
    fun overwriting(): EditorDocument {
        val open = conflict ?: return this
        return copy(revision = open.currentRevision, conflict = null)
    }

    companion object {
        /** A document as it arrives from `DeviceApi.get`. */
        fun loaded(text: String, revision: String): EditorDocument = EditorDocument(text, revision)
    }
}

/** The version that was on the server when a write arrived too late. */
data class EditorConflict(val currentRevision: String, val currentText: String)
