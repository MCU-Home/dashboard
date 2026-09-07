// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.ui.unit.dp
import org.mcuhome.ui.shell.WindowSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val phone = WindowSize(390.dp, 844.dp)
private val phoneLandscape = WindowSize(844.dp, 390.dp)
private val tablet = WindowSize(768.dp, 1024.dp)
private val tabletLandscape = WindowSize(1024.dp, 768.dp)
private val desktop = WindowSize(1440.dp, 900.dp)

/**
 * How a device page arranges itself before the user has said anything:
 * the rules the design states for each window, checked without one.
 */
class DeviceScreenLayoutTest {
    @Test
    fun a_tablet_held_upright_collapses_the_rail_and_opens_the_panel() {
        assertTrue(defaultRailCollapsed(tablet, narrow = false))
        assertFalse(defaultPanelMinimized(tablet))
    }

    @Test
    fun a_tablet_turned_sideways_opens_the_rail_and_puts_the_panel_away() {
        assertFalse(defaultRailCollapsed(tabletLandscape, narrow = false))
        assertTrue(defaultPanelMinimized(tabletLandscape))
    }

    @Test
    fun a_phone_has_no_rail_column_and_no_open_panel() {
        assertTrue(defaultRailCollapsed(phone, narrow = false))
        assertTrue(defaultPanelMinimized(phone))
        assertTrue(defaultPanelMinimized(phoneLandscape))
    }

    @Test
    fun a_desktop_window_decides_the_rail_by_its_own_width() {
        assertFalse(defaultRailCollapsed(desktop, narrow = false))
        assertTrue(defaultRailCollapsed(desktop, narrow = true))
        assertFalse(defaultPanelMinimized(desktop))
    }

    @Test
    fun only_a_phone_has_an_editing_mode() {
        assertTrue(editingMode(phone, editorFocused = true))
        assertTrue(editingMode(phoneLandscape, editorFocused = true))
        assertFalse(editingMode(phone, editorFocused = false))
        assertFalse(editingMode(tablet, editorFocused = true))
        assertFalse(editingMode(desktop, editorFocused = true))
    }

    @Test
    fun the_header_takes_the_shape_the_situation_calls_for() {
        assertEquals(PhoneHeaderMode.Portrait, phoneHeaderMode(editing = false, landscape = false))
        assertEquals(PhoneHeaderMode.Landscape, phoneHeaderMode(editing = false, landscape = true))
        assertEquals(PhoneHeaderMode.Editing, phoneHeaderMode(editing = true, landscape = false))
        assertEquals(PhoneHeaderMode.Editing, phoneHeaderMode(editing = true, landscape = true))
    }
}
