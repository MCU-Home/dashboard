// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the window offers, in three steps.
 *
 * Every layout decision in the interface is taken from this one value
 * rather than from a pixel figure of its own, so that "what a phone
 * gets" is decided once and the screens only say what they do about it.
 */
enum class WindowSizeClass {
    /** A phone: one column, the navigation behind a button, actions on a bar. */
    Compact,

    /** A tablet: the desktop layout with the columns that do not fit taken out. */
    Medium,

    /** A desktop window: the layout the design was drawn for. */
    Expanded,
}

/** Below this width a window is a phone. */
private val COMPACT_WIDTH = 600.dp

/** From this width on a window is a desktop window. */
private val EXPANDED_WIDTH = 1180.dp

/**
 * Below this height a window is treated as a phone whatever its width
 * is: a phone held sideways is 844 by 390, which is wide enough for a
 * tablet layout and far too short for one.
 */
private val COMPACT_HEIGHT = 480.dp

/**
 * The size of the window the interface is drawn in, and what follows
 * from it.
 *
 * The two figures are the ones the application root measured, in
 * density-independent pixels, so a screen can still ask for the actual
 * width where a class is too coarse — the width a dialog may take, for
 * instance.
 */
@Immutable
data class WindowSize(val width: Dp, val height: Dp) {
    val sizeClass: WindowSizeClass get() = when {
        width < COMPACT_WIDTH || height < COMPACT_HEIGHT -> WindowSizeClass.Compact
        width < EXPANDED_WIDTH -> WindowSizeClass.Medium
        else -> WindowSizeClass.Expanded
    }

    /** A phone, or a window as small as one. */
    val compact: Boolean get() = sizeClass == WindowSizeClass.Compact

    /** A tablet, or a window as large as one and no larger. */
    val medium: Boolean get() = sizeClass == WindowSizeClass.Medium

    /** The window the design was drawn for. */
    val expanded: Boolean get() = sizeClass == WindowSizeClass.Expanded

    /** Wider than it is tall — which decides what a phone or a tablet shows. */
    val landscape: Boolean get() = width > height
}

/**
 * The window size in scope, measured once at the application root.
 *
 * The chrome around the screens — the top bar, the jobs chip, a device's
 * header — reads it directly rather than being told, so that adding a
 * screen does not mean threading a parameter through it. The default is
 * a desktop window, which is what a composable outside the application
 * (a test, a preview) should see.
 */
val LocalWindowSize = compositionLocalOf { WindowSize(1920.dp, 1080.dp) }

/**
 * The smallest square a control may be drawn in so that a finger can hit
 * it: 44 px where the interface is used by touch, 32 px where a pointer
 * is doing the aiming.
 */
fun minimumTouchTarget(sizeClass: WindowSizeClass): Dp = if (sizeClass == WindowSizeClass.Compact) 44.dp else 32.dp
