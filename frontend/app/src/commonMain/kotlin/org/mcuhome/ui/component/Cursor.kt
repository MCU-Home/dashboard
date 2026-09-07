// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * The pointer a control that acts shows under the mouse.
 *
 * On a canvas there is no browser default to fall back on: nothing in a
 * Compose window is a link or a `<button>`, so every clickable thing has
 * to say so itself. It is applied inside the shared controls — buttons,
 * icon buttons, pills that act, table rows, tabs, menu entries — so a new
 * screen gets it by using them rather than by remembering to add it.
 *
 * [enabled] is the same flag the control's `clickable` carries: a
 * disabled control does nothing, and promising otherwise under the
 * pointer would be a lie.
 */
@Stable
fun Modifier.handCursor(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this
