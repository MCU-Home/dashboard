// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How the user last left the output panel, kept for as long as the page
 * is open in the browser.
 *
 * The panel is part of the workplace rather than part of one device: a
 * layout that is dragged into shape once should still be that shape after
 * a walk through the device list. It therefore lives above the navigation
 * graph, where a destination leaving the composition cannot take it with
 * it. Nothing is written to disk — the next visit starts from the design's
 * own defaults.
 */
class PanelSession {
    var layout: PanelLayout by mutableStateOf(PanelLayout())

    /**
     * Whether the user has minimized or restored the panel themselves.
     *
     * Which state the panel starts in depends on the window: open below
     * the editor on a desktop or a tablet held upright, out of the way on
     * a tablet turned sideways or on a phone, where the editor needs the
     * height more than the output does. That is a default, not a rule —
     * once the user has answered the question, the window stops answering
     * it for them.
     */
    var minimizedChosen: Boolean = false
}

val LocalPanelSession = staticCompositionLocalOf { PanelSession() }
