// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * How long the pointer has to rest on a control before its tooltip
 * appears. Long enough that crossing the icon strip on the way somewhere
 * else leaves the screen quiet, short enough that stopping on an icon
 * answers the question without a wait.
 */
private const val TOOLTIP_DELAY_MILLIS = 500L

private val TooltipShape = RoundedCornerShape(6.dp)

/** The gap between the control and the label under it. */
private val TooltipGap = 6.dp

/**
 * Hangs the label under the control it explains, right edges lined up, and
 * keeps it inside the window.
 *
 * Lining the right edges up rather than centring the label is what makes
 * it usable for the icon strips at the right edge of the page: a centred
 * label would be pushed back into the window and would then cover the
 * icon below the one being explained.
 */
private class TooltipBelowAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = (anchorBounds.bottom + gap)
            .coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
        return IntOffset(x, y)
    }
}

/**
 * A short label that appears under a control once the pointer has rested
 * on it: what the control is, and what it currently says.
 *
 * Icons carry the state of a whole section in the collapsed status rail
 * and in a minimized output panel, where there is no room for a word — so
 * the words are given back on hover instead of guessed at. It draws
 * itself from the theme's tokens, which means it reads correctly on the
 * light page and on the panel's dark surface without being told which one
 * it is on.
 */
@Composable
fun Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    val gap = with(LocalDensity.current) { TooltipGap.roundToPx() }
    var hovering by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(hovering, text) {
        if (!hovering) {
            showing = false
            return@LaunchedEffect
        }
        delay(TOOLTIP_DELAY_MILLIS)
        showing = true
    }
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // Read in the initial pass and consume nothing: the
                    // control inside still receives every event, and the
                    // tooltip only listens in.
                    when (awaitPointerEvent(PointerEventPass.Initial).type) {
                        PointerEventType.Enter -> hovering = true
                        PointerEventType.Exit -> hovering = false
                        PointerEventType.Press -> hovering = false
                        else -> Unit
                    }
                }
            }
        },
    ) {
        content()
        if (showing) {
            Popup(
                popupPositionProvider = remember(gap) { TooltipBelowAnchor(gap) },
                properties = PopupProperties(focusable = false, clippingEnabled = true),
            ) {
                Text(
                    text = text,
                    color = colors.ink,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .shadow(elevation = 8.dp, shape = TooltipShape)
                        .clip(TooltipShape)
                        .background(colors.surface)
                        .border(width = 1.dp, color = colors.border, shape = TooltipShape)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}
