// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

/**
 * The syntax roles the design gives YAML: keys, values, the `!secret` and
 * `!include` tags, plain scalars that are numbers or booleans, and
 * comments. Which color each one takes is decided by the theme, not here.
 */
enum class YamlToken { Key, Value, Tag, Literal, Comment }

/** One highlighted stretch of the document, as offsets into the text. */
data class YamlSpan(val start: Int, val end: Int, val token: YamlToken)

private val LITERALS = setOf(
    "true", "false", "yes", "no", "on", "off", "null", "~",
    "True", "False", "Yes", "No", "On", "Off", "Null", "NULL", "TRUE", "FALSE",
)

/**
 * A line-oriented scanner over a YAML document. It knows the shape of the
 * files MCUHome writes — block mappings, block sequences, tagged scalars,
 * comments — and deliberately not the whole YAML grammar: flow
 * collections, multi-line scalars and anchors are left unhighlighted
 * rather than highlighted wrongly.
 */
fun highlightYaml(text: CharSequence): List<YamlSpan> {
    val spans = mutableListOf<YamlSpan>()
    var lineStart = 0
    while (lineStart <= text.length) {
        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        scanLine(text, lineStart, lineEnd, spans)
        if (lineEnd >= text.length) break
        lineStart = lineEnd + 1
    }
    return spans
}

private fun scanLine(text: CharSequence, lineStart: Int, lineEnd: Int, out: MutableList<YamlSpan>) {
    var cursor = lineStart
    while (cursor < lineEnd && (text[cursor] == ' ' || text[cursor] == '\t')) cursor++
    if (cursor >= lineEnd) return

    if (text[cursor] == '#') {
        out += YamlSpan(cursor, lineEnd, YamlToken.Comment)
        return
    }

    // A document marker is left alone.
    if (text.startsWith("---", cursor) || text.startsWith("...", cursor)) return

    // Block sequence entry: the dash belongs to the structure, what
    // follows is scanned like any other line content.
    if (text[cursor] == '-' && (cursor + 1 == lineEnd || text[cursor + 1] == ' ')) {
        cursor++
        while (cursor < lineEnd && text[cursor] == ' ') cursor++
        if (cursor >= lineEnd) return
    }

    val commentStart = findTrailingComment(text, cursor, lineEnd)
    val contentEnd = commentStart ?: lineEnd
    if (commentStart != null) out += YamlSpan(commentStart, lineEnd, YamlToken.Comment)
    if (cursor >= contentEnd) return

    val colon = findKeyColon(text, cursor, contentEnd)
    var valueStart = cursor
    if (colon != null) {
        out += YamlSpan(cursor, colon, YamlToken.Key)
        valueStart = colon + 1
        while (valueStart < contentEnd && text[valueStart] == ' ') valueStart++
    }
    if (valueStart >= contentEnd) return
    scanValue(text, valueStart, contentEnd, out)
}

private fun scanValue(text: CharSequence, start: Int, end: Int, out: MutableList<YamlSpan>) {
    var cursor = start
    if (text[cursor] == '!') {
        var tagEnd = cursor
        while (tagEnd < end && !text[tagEnd].isWhitespace()) tagEnd++
        out += YamlSpan(cursor, tagEnd, YamlToken.Tag)
        cursor = tagEnd
        while (cursor < end && text[cursor] == ' ') cursor++
        if (cursor >= end) return
    }
    val scalar = text.subSequence(cursor, end).toString().trimEnd()
    if (scalar.isEmpty()) return
    val token = if (scalar in LITERALS || isNumber(scalar)) YamlToken.Literal else YamlToken.Value
    out += YamlSpan(cursor, cursor + scalar.length, token)
}

private fun isNumber(scalar: String): Boolean =
    scalar.toDoubleOrNull() != null ||
        (scalar.startsWith("0x") && scalar.drop(2).isNotEmpty() && scalar.drop(2).all { it.isHexDigit() })

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** The first `:` that ends a key: followed by a space or by the end of the content, outside quotes. */
private fun findKeyColon(text: CharSequence, start: Int, end: Int): Int? {
    var quote: Char? = null
    for (index in start until end) {
        val char = text[index]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == ':' && (index + 1 == end || text[index + 1] == ' ') -> return index
        }
    }
    return null
}

/** The `#` that starts a trailing comment: preceded by a space, outside quotes. */
private fun findTrailingComment(text: CharSequence, start: Int, end: Int): Int? {
    var quote: Char? = null
    for (index in start until end) {
        val char = text[index]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '#' && index > start && text[index - 1] == ' ' -> return index
        }
    }
    return null
}
