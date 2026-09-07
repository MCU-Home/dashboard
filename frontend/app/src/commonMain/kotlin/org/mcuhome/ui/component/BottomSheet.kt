// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mcuhome.ui.theme.MCUHomeTheme

private val SheetShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)

/** The row the grip is drawn in — and the strip a finger drags. */
private val GripRowHeight = 20.dp

private val GripWidth = 40.dp
private val GripHeight = 4.dp
private val GripShape = RoundedCornerShape(2.dp)

/**
 * A panel that comes up from the bottom edge over the screen it belongs
 * to: how a phone shows something that is too large for the page and too
 * small for a page of its own.
 *
 * It is drawn into the area it covers rather than into a popup, so it
 * stops where that area stops — the device page's sheet leaves the
 * header and the state above it in view, which is what the design draws
 * and what makes it obvious what the sheet belongs to.
 *
 * The grip at the top is a real handle: dragging it up and down changes
 * the sheet's height between [minHeight] and everything the area has.
 * Touching the screen above the sheet closes it.
 */
@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MCUHomeTheme.colors.surface,
    gripColor: Color = MCUHomeTheme.colors.border,
    initialHeightFraction: Float = DEFAULT_HEIGHT_FRACTION,
    minHeight: Dp = 160.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val dismissInteraction = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val available = maxHeight
        var height by remember(available) { mutableStateOf(available * initialHeightFraction) }
        val lowest = minOf(minHeight, available)
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = dismissInteraction, indication = null, onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height.coerceIn(lowest, available))
                .clip(SheetShape)
                .background(background),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GripRowHeight)
                    .verticalResizeCursor()
                    .pointerInput(available, lowest) {
                        detectVerticalDragGestures { _, dragAmount ->
                            val moved = with(density) { dragAmount.toDp() }
                            height = (height - moved).coerceIn(lowest, available)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = GripWidth, height = GripHeight).clip(GripShape).background(gripColor))
            }
            content()
        }
    }
}

/** How much of the area a sheet takes when it opens. */
private const val DEFAULT_HEIGHT_FRACTION = 0.78f
