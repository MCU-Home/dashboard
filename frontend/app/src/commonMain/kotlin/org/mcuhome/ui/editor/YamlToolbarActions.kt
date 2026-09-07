// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

/** What YAML calls one level: two spaces, never a tab. */
private const val INDENT = "  "

/**
 * Adds one level of indentation to every line the selection touches.
 *
 * A caret with nothing selected touches one line, which is what the
 * button does most of the time: it indents the line being typed.
 */
fun indentSelection(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
): TextEdit {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)
    val lines = text.split("\n").toMutableList()
    val first = lineIndexAt(text, start)
    val last = lineIndexAt(text, end)
    for (index in first..last) {
        lines[index] = INDENT + lines[index]
    }
    return TextEdit(
        text = lines.joinToString("\n"),
        selectionStart = start + INDENT.length,
        selectionEnd = end + INDENT.length * (last - first + 1),
    )
}

/**
 * Takes one level of indentation off every line the selection touches,
 * as far as each line has one to give.
 */
fun outdentSelection(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
): TextEdit {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)
    val lines = text.split("\n").toMutableList()
    val first = lineIndexAt(text, start)
    val last = lineIndexAt(text, end)
    var removedOnFirstLine = 0
    var removed = 0
    for (index in first..last) {
        val take = lines[index].takeWhile { it == ' ' }.length.coerceAtMost(INDENT.length)
        lines[index] = lines[index].substring(take)
        if (index == first) removedOnFirstLine = take
        removed += take
    }
    val newStart = (start - removedOnFirstLine).coerceAtLeast(0)
    return TextEdit(
        text = lines.joinToString("\n"),
        selectionStart = newStart,
        selectionEnd = (end - removed).coerceAtLeast(newStart),
    )
}

/**
 * Puts [insert] where the caret is, replacing whatever was selected, and
 * leaves the caret behind it — what the `:`, `-` and `!secret` keys do.
 */
fun insertAtSelection(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    insert: String,
): TextEdit {
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)
    val caret = start + insert.length
    return TextEdit(
        text = text.substring(0, start) + insert + text.substring(end),
        selectionStart = caret,
        selectionEnd = caret,
    )
}

/** Which line an offset falls on, counted from zero. */
private fun lineIndexAt(text: String, offset: Int): Int =
    text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' }
