// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals

private const val DOCUMENT = "device:\n  board: nrf52840dk\n  name: hallway\n"

/**
 * What the YAML toolbar's keys do to the text: the part of the editing
 * mode that has nothing to do with a keyboard being on screen.
 */
class YamlToolbarActionsTest {
    @Test
    fun indent_adds_a_level_to_the_line_the_caret_is_on() {
        val caret = DOCUMENT.indexOf("  name")
        val edit = indentSelection(DOCUMENT, caret, caret)
        assertEquals("device:\n  board: nrf52840dk\n    name: hallway\n", edit.text)
        assertEquals(caret + 2, edit.selectionStart)
        assertEquals(caret + 2, edit.selectionEnd)
    }

    @Test
    fun indent_adds_a_level_to_every_line_the_selection_touches() {
        val from = DOCUMENT.indexOf("  board")
        val to = DOCUMENT.indexOf("hallway")
        val edit = indentSelection(DOCUMENT, from, to)
        assertEquals("device:\n    board: nrf52840dk\n    name: hallway\n", edit.text)
        assertEquals(from + 2, edit.selectionStart)
        assertEquals(to + 4, edit.selectionEnd)
    }

    @Test
    fun outdent_takes_a_level_off_and_stops_at_the_left_edge() {
        val once = outdentSelection(DOCUMENT, DOCUMENT.indexOf("  name"), DOCUMENT.indexOf("  name"))
        assertEquals("device:\n  board: nrf52840dk\nname: hallway\n", once.text)
        val onLine = once.text.indexOf("name:")
        val twice = outdentSelection(once.text, onLine, onLine)
        assertEquals(once.text, twice.text)
    }

    @Test
    fun outdent_takes_what_each_line_has_to_give() {
        val text = "a:\n b:\n    c:\n"
        val edit = outdentSelection(text, 0, text.length)
        assertEquals("a:\nb:\n  c:\n", edit.text)
        assertEquals(0, edit.selectionStart)
        assertEquals(text.length - 3, edit.selectionEnd)
    }

    @Test
    fun a_key_puts_its_text_where_the_caret_is() {
        val edit = insertAtSelection("name", 4, 4, ": ")
        assertEquals("name: ", edit.text)
        assertEquals(6, edit.selectionStart)
        assertEquals(6, edit.selectionEnd)
    }

    @Test
    fun a_key_replaces_what_is_selected() {
        val edit = insertAtSelection("name: value", 6, 11, "!secret ")
        assertEquals("name: !secret ", edit.text)
        assertEquals(14, edit.selectionStart)
    }

    @Test
    fun an_offset_outside_the_document_does_not_throw() {
        val edit = indentSelection("a:", 99, 99)
        assertEquals("  a:", edit.text)
    }
}
