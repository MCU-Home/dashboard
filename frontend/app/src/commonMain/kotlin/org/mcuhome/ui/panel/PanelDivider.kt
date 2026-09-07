// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mcuhome.ui.theme.MCUHomeTheme

/** How thick the draggable strip between two areas is. */
private val DividerThickness = 8.dp

/** The grip drawn in the middle of a divider, so it is visibly draggable. */
private val GripLength = 36.dp
private val GripThickness = 3.dp

/**
 * The strip between the editor column and a panel docked below it.
 * Dragging it upwards makes the panel taller, which is the direction the
 * caller is handed.
 */
@Composable
fun HorizontalPanelDivider(onDrag: (Dp) -> Unit, modifier: Modifier = Modifier) {
    val colors = MCUHomeTheme.colors
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DividerThickness)
            .background(colors.background)
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onDrag(with(density) { (-amount.y).toDp() })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = GripLength, height = GripThickness)
                .clip(RoundedCornerShape(GripThickness / 2))
                .background(colors.border),
        )
    }
}

/**
 * The strip between the status rail and a panel docked at the right.
 * Dragging it leftwards makes the panel wider.
 */
@Composable
fun VerticalPanelDivider(onDrag: (Dp) -> Unit, modifier: Modifier = Modifier) {
    val colors = MCUHomeTheme.colors
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(DividerThickness)
            .background(colors.background)
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onDrag(with(density) { (-amount.x).toDp() })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = GripThickness, height = GripLength)
                .clip(RoundedCornerShape(GripThickness / 2))
                .background(colors.border),
        )
    }
}
