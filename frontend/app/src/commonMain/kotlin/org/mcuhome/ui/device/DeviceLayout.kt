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
import org.mcuhome.ui.component.BottomSheet
import org.mcuhome.ui.panel.HorizontalPanelDivider
import org.mcuhome.ui.panel.OutputPanel
import org.mcuhome.ui.panel.OutputPanelActions
import org.mcuhome.ui.panel.OutputPanelData
import org.mcuhome.ui.panel.PanelDock
import org.mcuhome.ui.panel.PanelLayout
import org.mcuhome.ui.panel.VerticalPanelDivider
import org.mcuhome.ui.theme.DarkSchemeContent
import org.mcuhome.ui.theme.MCUHomeTheme

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

/**
 * The same three areas on a phone.
 *
 * Upright there is room for one column: the editor, with the minimized
 * output under it and everything else either above it or one tap away.
 * Turned sideways the height is gone instead of the width, so the status
 * rail comes back as its icon strip beside the editor and the output
 * keeps its line under it — the arrangement a desktop window has, at a
 * quarter of the size.
 */
@Composable
fun CompactDeviceBody(
    landscape: Boolean,
    slots: CompactDeviceBodySlots,
    modifier: Modifier = Modifier,
) {
    if (landscape) {
        Row(modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.weight(1f)) { slots.editor() }
                slots.minimizedBar()
            }
            slots.rail()
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { slots.editor() }
        slots.minimizedBar()
    }
}

/** The three areas [CompactDeviceBody] arranges; each may draw nothing. */
class CompactDeviceBodySlots(
    val editor: @Composable () -> Unit,
    val rail: @Composable () -> Unit,
    val minimizedBar: @Composable () -> Unit,
)

/**
 * The output panel on a phone: a sheet that comes up over the editor,
 * with the grip that sizes it and the five tabs it always has.
 *
 * Touching the page above it, or its own minimize button, puts it back
 * into the line at the bottom — a phone has one place for the panel, so
 * the button that moves it between two edges is not offered here.
 */
@Composable
fun PanelSheet(
    layout: PanelLayout,
    data: OutputPanelData,
    actions: OutputPanelActions,
    modifier: Modifier = Modifier,
) {
    DarkSchemeContent {
        val colors = MCUHomeTheme.colors
        BottomSheet(
            onDismiss = { actions.onLayout(layout.minimized()) },
            modifier = modifier,
            background = colors.background,
            gripColor = colors.muted,
        ) {
            OutputPanel(layout, data, actions, Modifier.fillMaxSize(), withDockToggle = false)
        }
    }
}

/** The status rail on a phone: the same blocks, in a sheet instead of a column. */
@Composable
fun RailSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BottomSheet(onDismiss = onDismiss, modifier = modifier) { content() }
}
