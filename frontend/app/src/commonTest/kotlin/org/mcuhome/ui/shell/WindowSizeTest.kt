// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowSizeTest {
    @Test
    fun phone_upright_is_compact() {
        assertEquals(WindowSizeClass.Compact, WindowSize(390.dp, 844.dp).sizeClass)
    }

    @Test
    fun phone_sideways_is_compact_although_it_is_wide() {
        // 844 dp is wider than a tablet held upright; 390 dp of height is
        // what makes it a phone.
        assertEquals(WindowSizeClass.Compact, WindowSize(844.dp, 390.dp).sizeClass)
    }

    @Test
    fun tablet_upright_is_medium() {
        assertEquals(WindowSizeClass.Medium, WindowSize(768.dp, 1024.dp).sizeClass)
    }

    @Test
    fun tablet_sideways_is_medium() {
        assertEquals(WindowSizeClass.Medium, WindowSize(1024.dp, 768.dp).sizeClass)
    }

    @Test
    fun desktop_is_expanded() {
        assertEquals(WindowSizeClass.Expanded, WindowSize(1440.dp, 900.dp).sizeClass)
        assertEquals(WindowSizeClass.Expanded, WindowSize(1920.dp, 1080.dp).sizeClass)
    }

    @Test
    fun the_width_boundaries_are_where_the_design_puts_them() {
        assertEquals(WindowSizeClass.Compact, WindowSize(599.dp, 900.dp).sizeClass)
        assertEquals(WindowSizeClass.Medium, WindowSize(600.dp, 900.dp).sizeClass)
        assertEquals(WindowSizeClass.Medium, WindowSize(1179.dp, 900.dp).sizeClass)
        assertEquals(WindowSizeClass.Expanded, WindowSize(1180.dp, 900.dp).sizeClass)
    }

    @Test
    fun a_window_too_short_for_a_tablet_layout_is_compact() {
        assertEquals(WindowSizeClass.Compact, WindowSize(1440.dp, 479.dp).sizeClass)
        assertEquals(WindowSizeClass.Expanded, WindowSize(1440.dp, 480.dp).sizeClass)
    }

    @Test
    fun orientation_compares_the_two_sides() {
        assertTrue(WindowSize(844.dp, 390.dp).landscape)
        assertFalse(WindowSize(390.dp, 844.dp).landscape)
        assertFalse(WindowSize(500.dp, 500.dp).landscape)
    }

    @Test
    fun touch_targets_are_larger_where_a_finger_aims() {
        assertEquals(44.dp, minimumTouchTarget(WindowSizeClass.Compact))
        assertEquals(32.dp, minimumTouchTarget(WindowSizeClass.Medium))
        assertEquals(32.dp, minimumTouchTarget(WindowSizeClass.Expanded))
    }
}
