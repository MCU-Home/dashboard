// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import kotlin.test.Test
import kotlin.test.assertEquals

class PanelControlsTest {
    @Test
    fun the_dock_button_names_the_edge_the_click_leads_to() {
        val bottom = PanelLayout()
        assertEquals("Dock right", dockToggleLabel(bottom.otherDock))
        assertEquals("Dock bottom", dockToggleLabel(bottom.dockToggled().otherDock))
    }

    @Test
    fun a_minimized_panel_offers_every_tab_the_open_one_has() {
        assertEquals(
            listOf("Build", "Diagnostics", "Device log", "Model", "Artifacts"),
            PanelTab.entries.map { it.label },
        )
    }

    @Test
    fun every_tab_has_an_icon_of_its_own() {
        val icons = PanelTab.entries.map { tabIcon(it).name }
        assertEquals(icons.size, icons.toSet().size)
    }

    @Test
    fun a_tab_label_carries_the_diagnostics_count_and_nothing_else() {
        assertEquals("Diagnostics — 2", minimizedTabLabel(PanelTab.Diagnostics, diagnosticCount = 2))
        assertEquals("Diagnostics", minimizedTabLabel(PanelTab.Diagnostics, diagnosticCount = 0))
        assertEquals("Build", minimizedTabLabel(PanelTab.Build, diagnosticCount = 2))
    }

    @Test
    fun the_panel_disappears_and_returns_in_the_direction_it_is_docked() {
        assertEquals("collapseDown", minimizeIcon(PanelDock.Bottom).name)
        assertEquals("collapseRight", minimizeIcon(PanelDock.Right).name)
        assertEquals("chevronUp", restoreIcon(PanelDock.Bottom).name)
        assertEquals("chevronLeft", restoreIcon(PanelDock.Right).name)
    }
}
