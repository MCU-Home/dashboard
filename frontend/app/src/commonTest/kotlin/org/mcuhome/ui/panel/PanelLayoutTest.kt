// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelLayoutTest {
    private val layout = PanelLayout()

    @Test
    fun the_panel_starts_at_the_bottom_on_the_build_tab() {
        assertEquals(PanelDock.Bottom, layout.dock)
        assertEquals(PanelTab.Build, layout.tab)
        assertFalse(layout.minimized)
        assertEquals(layout.bottomHeight, layout.size)
    }

    @Test
    fun the_dock_button_of_a_panel_at_the_bottom_points_at_the_right_edge() {
        assertEquals(PanelDock.Right, layout.otherDock)
        assertEquals(PanelDock.Bottom, layout.dockToggled().otherDock)
    }

    @Test
    fun docking_at_the_right_reports_the_right_width_as_its_size() {
        val right = layout.dockToggled()
        assertEquals(PanelDock.Right, right.dock)
        assertEquals(right.rightWidth, right.size)
    }

    @Test
    fun the_dock_button_moves_the_panel_without_opening_or_closing_it() {
        val minimized = layout.minimized()
        assertTrue(minimized.minimized)
        val moved = minimized.dockToggled()
        assertEquals(PanelDock.Right, moved.dock)
        assertTrue(moved.minimized)
        assertFalse(layout.dockToggled().minimized)
    }

    @Test
    fun the_dock_button_is_its_own_way_back() {
        assertEquals(layout, layout.dockToggled().dockToggled())
    }

    @Test
    fun choosing_a_tab_opens_a_minimized_panel_on_that_tab() {
        val shown = layout.minimized().showing(PanelTab.Artifacts)
        assertEquals(PanelTab.Artifacts, shown.tab)
        assertFalse(shown.minimized)
    }

    @Test
    fun each_edge_keeps_its_own_size_when_the_other_one_is_dragged() {
        val resized = layout.resized(60.dp)
        assertEquals(layout.bottomHeight + 60.dp, resized.bottomHeight)
        assertEquals(layout.rightWidth, resized.rightWidth)

        val right = layout.dockToggled().resized(40.dp)
        assertEquals(layout.rightWidth + 40.dp, right.rightWidth)
        assertEquals(layout.bottomHeight, right.bottomHeight)
    }

    @Test
    fun a_drag_beyond_the_limits_stops_at_them() {
        assertEquals(140.dp, layout.resized((-2000).dp).bottomHeight)
        assertEquals(700.dp, layout.resized(2000.dp).bottomHeight)
        val right = layout.dockToggled()
        assertEquals(280.dp, right.resized((-2000).dp).rightWidth)
        assertEquals(820.dp, right.resized(2000.dp).rightWidth)
    }

    @Test
    fun restoring_and_minimizing_are_the_two_directions_of_one_switch() {
        assertTrue(layout.minimized().minimized)
        assertFalse(layout.minimized().restored().minimized)
    }

    @Test
    fun minimizing_keeps_the_tab_that_was_open() {
        val minimized = layout.showing(PanelTab.Diagnostics).minimized()
        assertEquals(PanelTab.Diagnostics, minimized.tab)
        assertEquals(PanelTab.Diagnostics, minimized.restored().tab)
    }
}
