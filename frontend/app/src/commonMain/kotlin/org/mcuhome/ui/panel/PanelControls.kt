// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Tooltip

/**
 * What the dock button promises: the edge the panel moves to, not the one
 * it is on. The button is one toggle rather than one button per edge, so
 * its label is the only thing that says where the click leads.
 */
fun dockToggleLabel(target: PanelDock): String = when (target) {
    PanelDock.Bottom -> "Dock bottom"
    PanelDock.Right -> "Dock right"
}

/** The direction the panel disappears in when it is minimized where it is. */
fun minimizeIcon(dock: PanelDock): ImageVector = when (dock) {
    PanelDock.Bottom -> MCUHomeIcons.collapseDown
    PanelDock.Right -> MCUHomeIcons.collapseRight
}

/** The direction the minimized panel comes back from. */
fun restoreIcon(dock: PanelDock): ImageVector = when (dock) {
    PanelDock.Bottom -> MCUHomeIcons.chevronUp
    PanelDock.Right -> MCUHomeIcons.chevronLeft
}

/**
 * The button that moves the panel to the other edge. It is drawn in the
 * open panel's corner and in both minimized states, and does the same
 * thing in all three: a panel that was put away stays away, it is only
 * put away somewhere else.
 */
@Composable
fun PanelDockToggle(
    layout: PanelLayout,
    actions: OutputPanelActions,
    modifier: Modifier = Modifier,
) {
    val label = dockToggleLabel(layout.otherDock)
    Tooltip(text = label, modifier = modifier) {
        MCUHomeIconButton(
            icon = MCUHomeIcons.dockToggle,
            contentDescription = label,
            onClick = { actions.onLayout(layout.dockToggled()) },
        )
    }
}

/** The button that puts the panel away without losing what it shows. */
@Composable
fun PanelMinimizeButton(
    layout: PanelLayout,
    actions: OutputPanelActions,
    modifier: Modifier = Modifier,
) {
    Tooltip(text = "Minimize", modifier = modifier) {
        MCUHomeIconButton(
            icon = minimizeIcon(layout.dock),
            contentDescription = "Minimize the panel",
            onClick = { actions.onLayout(layout.minimized()) },
        )
    }
}

/** The button that brings the whole panel back, on the tab it was left on. */
@Composable
fun PanelRestoreButton(
    layout: PanelLayout,
    actions: OutputPanelActions,
    modifier: Modifier = Modifier,
) {
    Tooltip(text = "Restore", modifier = modifier) {
        MCUHomeIconButton(
            icon = restoreIcon(layout.dock),
            contentDescription = "Show the panel",
            onClick = { actions.onLayout(layout.restored()) },
        )
    }
}

/**
 * What one tab of a minimized panel is called when there is no room for
 * the whole word — the strip's tooltip, and the bar's.
 *
 * Diagnostics is the one tab whose name alone says too little: the number
 * beside it in the open panel is the reason a user looks at it at all, so
 * a minimized panel carries it into the label.
 */
fun minimizedTabLabel(tab: PanelTab, diagnosticCount: Int): String =
    if (tab == PanelTab.Diagnostics && diagnosticCount > 0) "${tab.label} — $diagnosticCount" else tab.label

/** The icon that stands for a tab where the panel is too narrow for its name. */
fun tabIcon(tab: PanelTab): ImageVector = when (tab) {
    PanelTab.Build -> MCUHomeIcons.hammer
    PanelTab.Diagnostics -> MCUHomeIcons.warningTriangle
    PanelTab.DeviceLog -> MCUHomeIcons.logLines
    PanelTab.Model -> MCUHomeIcons.file
    PanelTab.Artifacts -> MCUHomeIcons.download
}
