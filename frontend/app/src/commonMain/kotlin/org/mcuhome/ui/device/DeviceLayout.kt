// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import org.mcuhome.ui.panel.HorizontalPanelDivider
import org.mcuhome.ui.panel.PanelDock
import org.mcuhome.ui.panel.PanelLayout
import org.mcuhome.ui.panel.VerticalPanelDivider

/**
 * How the three areas of a device page are arranged: the editor, the
 * status rail beside it, and the output panel wherever the panel is
 * docked.
 *
 * The panel docked at the bottom belongs to the *editor column*, open as
 * well as minimized: it is the editor's output, and the rail beside it
 * keeps its full height either way — a status bar across the whole window
 * would take the bottom of the rail away for something that does not
 * belong to it.
 */
@Composable
fun DeviceBody(
    layout: PanelLayout,
    onResize: (Dp) -> Unit,
    slots: DeviceBodySlots,
    modifier: Modifier = Modifier,
) {
    when (layout.dock) {
        PanelDock.Bottom -> Row(modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.weight(1f)) { slots.editor() }
                if (layout.minimized) {
                    slots.minimizedBar()
                } else {
                    HorizontalPanelDivider(onResize)
                    Box(Modifier.height(layout.bottomHeight)) { slots.panel() }
                }
            }
            slots.rail()
        }

        PanelDock.Right -> Row(modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { slots.editor() }
            slots.rail()
            if (layout.minimized) {
                slots.minimizedStrip()
            } else {
                VerticalPanelDivider(onResize)
                Box(Modifier.width(layout.rightWidth)) { slots.panel() }
            }
        }
    }
}

/** The four areas [DeviceBody] arranges, each drawn by its owner. */
class DeviceBodySlots(
    val editor: @Composable () -> Unit,
    val rail: @Composable () -> Unit,
    val panel: @Composable () -> Unit,
    val minimizedBar: @Composable () -> Unit,
    val minimizedStrip: @Composable () -> Unit,
)
