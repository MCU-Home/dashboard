// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the user has said, this session, that the status rail should be
 * open or closed.
 *
 * The rail has a sensible state for every window size — open on a desktop
 * window, collapsed on a tablet held upright, an icon strip on a phone —
 * and taking it is right until the user says otherwise. From then on the
 * choice wins for as long as the page is open, which is why it is kept
 * above the navigation graph rather than inside the device page: walking
 * to another device and back is not a change of mind.
 *
 * `null` means the user has not chosen and the window decides.
 */
class RailSession {
    var collapsed: Boolean? by mutableStateOf(null)
}

val LocalRailSession = staticCompositionLocalOf { RailSession() }
