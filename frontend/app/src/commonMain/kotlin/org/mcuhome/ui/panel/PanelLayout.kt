// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where the output panel sits: under the editor column, or beside the rail. */
enum class PanelDock { Bottom, Right }

/** The five views the output panel offers, in the order it draws them. */
enum class PanelTab(val label: String) {
    Build("Build"),
    Diagnostics("Diagnostics"),
    DeviceLog("Device log"),
    Model("Model"),
    Artifacts("Artifacts"),
}

/** The height the minimized panel keeps at the bottom of the page. */
val MinimizedBarHeight = 36.dp

/** The width the minimized panel keeps at the right edge of the page. */
val MinimizedStripWidth = 40.dp

private val BottomMin = 140.dp
private val BottomMax = 700.dp
private val RightMin = 280.dp
private val RightMax = 820.dp

/**
 * The shape of the output panel: where it is docked, how big it is, which
 * tab is open, and whether it is minimized.
 *
 * It is a value with transitions rather than four pieces of state next to
 * each other, so the divider, the two dock buttons and the minimize
 * button cannot disagree about what the panel currently is — and what
 * each of them does can be checked without a browser.
 */
data class PanelLayout(
    val dock: PanelDock = PanelDock.Bottom,
    val tab: PanelTab = PanelTab.Build,
    val minimized: Boolean = false,
    val bottomHeight: Dp = 300.dp,
    val rightWidth: Dp = 440.dp,
) {
    /** How much room the panel takes in the direction it is docked. */
    val size: Dp get() = if (dock == PanelDock.Bottom) bottomHeight else rightWidth

    /** The edge the dock button moves the panel to when it is pressed. */
    val otherDock: PanelDock get() = if (dock == PanelDock.Bottom) PanelDock.Right else PanelDock.Bottom

    /**
     * Move the panel to the other edge.
     *
     * Whether it is open or minimized is left alone: the button says
     * where the panel sits, not how much of it is showing, and a panel
     * that was put away stays away until the button next to it says
     * otherwise.
     */
    fun dockToggled(): PanelLayout = copy(dock = otherDock)

    fun showing(tab: PanelTab): PanelLayout = copy(tab = tab, minimized = false)

    fun minimized(): PanelLayout = copy(minimized = true)

    fun restored(): PanelLayout = copy(minimized = false)

    /**
     * The divider was dragged by [delta] in the direction that makes the
     * panel bigger: upwards at the bottom, leftwards at the right. Each
     * edge keeps its own size, so switching back and forth does not
     * resize anything.
     */
    fun resized(delta: Dp): PanelLayout = when (dock) {
        PanelDock.Bottom -> copy(bottomHeight = (bottomHeight + delta).coerceIn(BottomMin, BottomMax))
        PanelDock.Right -> copy(rightWidth = (rightWidth + delta).coerceIn(RightMin, RightMax))
    }
}
