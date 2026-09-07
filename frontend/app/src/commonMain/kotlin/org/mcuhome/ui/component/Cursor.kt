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

/**
 * The pointer over the divider between two areas that are stacked, so
 * that dragging it makes the upper one taller or shorter.
 */
@Stable
fun Modifier.verticalResizeCursor(): Modifier = pointerHoverIcon(VerticalResizeCursor)

/** The pointer over the divider between two areas that stand side by side. */
@Stable
fun Modifier.horizontalResizeCursor(): Modifier = pointerHoverIcon(HorizontalResizeCursor)

/**
 * The two resize pointers, named by the platform underneath.
 *
 * `PointerIcon` carries four shapes every platform agrees on — the arrow,
 * the hand, the text caret and the crosshair — and a resize pointer is not
 * one of them. Each platform therefore names its own: in the browser that
 * is a CSS cursor keyword, elsewhere it would be the window system's
 * equivalent. The pair is declared here so that the code that draws a
 * divider asks for "the resize pointer" and nothing else.
 */
internal expect val VerticalResizeCursor: PointerIcon

internal expect val HorizontalResizeCursor: PointerIcon
