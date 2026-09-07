// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

/**
 * A document and where the caret stands in it, after one action of the
 * YAML toolbar.
 *
 * The toolbar's work is a text transformation and nothing else, so it is
 * written as one — a function from text and selection to text and
 * selection. What it does can then be checked without a text field, a
 * keyboard or a browser.
 */
data class TextEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)
