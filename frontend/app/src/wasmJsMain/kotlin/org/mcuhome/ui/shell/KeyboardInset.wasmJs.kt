// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.js.ExperimentalWasmJsInterop

/**
 * How often the visual viewport is read while a phone-sized window is on
 * screen, in milliseconds.
 *
 * The browser fires `resize` and `scroll` on `window.visualViewport`, but
 * that object is not part of the typed DOM the standard library declares
 * for this target, and a listener cannot be handed to it without a
 * JavaScript shim that captures a Kotlin function. Reading the two
 * numbers a few times a second costs nothing measurable and needs no
 * shim; the keyboard opening is a movement the user is watching anyway,
 * not a frame-accurate animation.
 *
 * This is the fallback path in the first place: where the browser honours
 * the page shell's `interactive-widget=resizes-content`, the window
 * Compose draws into becomes shorter by itself and the number read here
 * stays zero.
 */
private const val POLL_INTERVAL_MILLIS = 200L

/**
 * The height at the bottom of the layout viewport that the visual
 * viewport does not reach — the on-screen keyboard, in practice.
 *
 * `offsetTop` counts towards it: a browser that scrolls the visual
 * viewport up to keep the caret in sight hides that much more at the
 * bottom.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private fun bottomInsetPixels(): Double = js(
    """{
        if (!window.visualViewport) { return 0; }
        const viewport = window.visualViewport;
        return Math.max(0, window.innerHeight - (viewport.height + viewport.offsetTop));
    }""",
)

@Composable
actual fun rememberKeyboardInset(enabled: Boolean): Dp {
    val density = LocalDensity.current
    var pixels by remember { mutableStateOf(0.0) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            pixels = 0.0
            return@LaunchedEffect
        }
        while (true) {
            pixels = bottomInsetPixels()
            delay(POLL_INTERVAL_MILLIS)
        }
    }
    return with(density) { pixels.toFloat().toDp() }
}
