// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import org.mcuhome.ui.shell.WindowSize

// The rules by which a device page arranges itself: which state the
// output panel and the status rail start in, and when the page turns into
// an editing surface. They are written as functions of the window and
// nothing else, so that what a phone, a tablet and a desktop window get
// can be checked without any of the three.

/**
 * Whether the output panel starts out of the way.
 *
 * It does wherever the height is the scarce thing: on a phone, where
 * there is barely room for the editor, and on a tablet held sideways,
 * where the design puts the panel away and the status rail up.
 */
fun defaultPanelMinimized(window: WindowSize): Boolean = window.compact || (window.medium && window.landscape)

/**
 * Whether the status rail starts as its icon strip.
 *
 * A tablet held upright has no room for a 260 px column beside the
 * editor and one held sideways does; a desktop window decides by its
 * actual width, which is what [narrow] carries.
 */
fun defaultRailCollapsed(window: WindowSize, narrow: Boolean): Boolean = when {
    window.compact -> true
    window.medium -> !window.landscape
    else -> narrow
}

/** Which of its three shapes a phone's header takes. */
fun phoneHeaderMode(editing: Boolean, landscape: Boolean): PhoneHeaderMode = when {
    editing -> PhoneHeaderMode.Editing
    landscape -> PhoneHeaderMode.Landscape
    else -> PhoneHeaderMode.Portrait
}

/**
 * Whether the page is in its editing mode: everything but the editor
 * out of the way, and the YAML keys above the keyboard.
 *
 * Only a phone has one. A tablet and a desktop window have room for the
 * editor and everything around it at the same time, and a keyboard that
 * does not take half the screen when it opens.
 */
fun editingMode(window: WindowSize, editorFocused: Boolean): Boolean = window.compact && editorFocused
