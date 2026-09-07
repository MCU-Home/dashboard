// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the bottom of the window something outside the application
 * is covering — on a phone, the on-screen keyboard.
 *
 * Compose's own `WindowInsets.ime` is an Android concept; on the web
 * target it reports nothing, because the browser does not tell a page
 * that a keyboard is up. What the browser does offer is the *visual*
 * viewport: the part of the page the user can actually see. The
 * difference between it and the layout viewport is exactly the strip the
 * keyboard covers, and that is what the platform side measures.
 *
 * The value is zero on a platform that has no such thing, and zero in a
 * browser that shrinks the page itself when the keyboard opens (the
 * `interactive-widget=resizes-content` viewport setting the page shell
 * asks for): there the window Compose draws into has already become
 * shorter and nothing has to be added to it.
 */
val LocalKeyboardInset = compositionLocalOf { 0.dp }

/**
 * Follows the height the platform hides at the bottom of the window and
 * reports it as it changes.
 *
 * Called once, at the application root, and put into the composition as
 * [LocalKeyboardInset]; nothing else watches the viewport on its own.
 *
 * [enabled] is false wherever the answer cannot matter — a window that
 * is too large to be a phone has no on-screen keyboard in front of it —
 * and the platform side then does no work at all.
 */
@Composable
expect fun rememberKeyboardInset(enabled: Boolean): Dp
