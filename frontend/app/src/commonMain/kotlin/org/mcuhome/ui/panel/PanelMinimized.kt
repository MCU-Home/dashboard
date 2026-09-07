// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.StageState
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.DarkSchemeContent
import org.mcuhome.ui.theme.MCUHomeTheme

private val ProgressBarWidth = 220.dp

/** The strip reads from top to bottom: a quarter turn clockwise. */
private const val QUARTER_TURN = 90f

/**
 * Lays its content out sideways: measured as wide as it wants to be, but
 * reported — and drawn — turned a quarter clockwise, so a line of text
 * reads from top to bottom in a strip that is narrower than the words.
 *
 * Rotating with a draw modifier alone would leave the layout thinking the
 * text is still lying flat, and the column around it would reserve the
 * wrong space.
 */
private fun Modifier.turnedSideways(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxHeight = constraints.maxWidth))
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(x = 0, y = 0) {
            rotationZ = QUARTER_TURN
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = placeable.height.toFloat()
        }
    }
}

/**
 * The panel as a status bar across the bottom of the page: what the build
 * is doing, how many diagnostics there are, and the way back to the full
 * panel. Minimizing hides the output, not the state.
 */
@Composable
fun PanelMinimizedBar(
    layout: PanelLayout,
    data: OutputPanelData,
    actions: OutputPanelActions,
    modifier: Modifier = Modifier,
) {
    DarkSchemeContent {
        val colors = MCUHomeTheme.colors
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(MinimizedBarHeight)
                .background(colors.background)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.handCursor().clickable { actions.onLayout(layout.showing(PanelTab.Build)) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = MCUHomeIcons.hammer,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Build",
                    color = colors.ink,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp,
                )
            }
            val stage = data.build.snapshot?.stages?.firstOrNull { it.state == StageState.Running }
            if (stage != null) {
                Text(
                    text = stageLabel(stage.stage, stage.progress, stage.durationMillis),
                    color = colors.accent,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 12.5.sp,
                )
                Box(
                    Modifier
                        .width(ProgressBarWidth)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.backgroundAlt),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(data.build.progressFraction())
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accent),
                    )
                }
            }
            MinimizedEntry(
                label = "Diagnostics",
                count = data.diagnostics.size,
                onClick = { actions.onLayout(layout.showing(PanelTab.Diagnostics)) },
            )
            MinimizedEntry(
                label = "Device log",
                count = 0,
                onClick = { actions.onLayout(layout.showing(PanelTab.DeviceLog)) },
            )
            Box(Modifier.weight(1f))
            MCUHomeIconButton(
                icon = MCUHomeIcons.dockBottom,
                contentDescription = "Dock the panel at the bottom",
                onClick = { actions.onLayout(layout.dockedTo(PanelDock.Bottom)) },
            )
            MCUHomeIconButton(
                icon = MCUHomeIcons.dockRight,
                contentDescription = "Dock the panel at the right",
                onClick = { actions.onLayout(layout.dockedTo(PanelDock.Right)) },
            )
            MCUHomeIconButton(
                icon = MCUHomeIcons.chevronUp,
                contentDescription = "Show the panel",
                onClick = { actions.onLayout(layout.restored()) },
            )
        }
    }
}

@Composable
private fun MinimizedEntry(
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier.handCursor().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.5.sp,
        )
        if (count > 0) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.warningTintBorder)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = count.toString(),
                    color = colors.background,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * The panel as a strip down the right edge: the same state, turned on its
 * side, for a window that is being used from the side rather than from
 * the bottom.
 */
@Composable
fun PanelMinimizedStrip(
    layout: PanelLayout,
    data: OutputPanelData,
    actions: OutputPanelActions,
    modifier: Modifier = Modifier,
) {
    DarkSchemeContent {
        val colors = MCUHomeTheme.colors
        Column(
            modifier = modifier
                .fillMaxHeight()
                .width(MinimizedStripWidth)
                .background(colors.background)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MCUHomeIconButton(
                icon = MCUHomeIcons.chevronLeft,
                contentDescription = "Show the panel",
                onClick = { actions.onLayout(layout.restored()) },
            )
            MCUHomeIconButton(
                icon = MCUHomeIcons.hammer,
                contentDescription = "Show the build output",
                onClick = { actions.onLayout(layout.showing(PanelTab.Build)) },
                tint = colors.ink,
            )
            MCUHomeIconButton(
                icon = MCUHomeIcons.warningTriangle,
                contentDescription = "Show the diagnostics",
                onClick = { actions.onLayout(layout.showing(PanelTab.Diagnostics)) },
                tint = if (data.diagnostics.isEmpty()) colors.muted else colors.warning,
            )
            MCUHomeIconButton(
                icon = MCUHomeIcons.file,
                contentDescription = "Show the resolved model",
                onClick = { actions.onLayout(layout.showing(PanelTab.Model)) },
            )
            val stage = data.build.snapshot?.stages?.firstOrNull { it.state == StageState.Running }
            if (stage != null) {
                Text(
                    text = stageLabel(stage.stage, stage.progress, stage.durationMillis),
                    color = colors.accent,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp).turnedSideways(),
                )
                Box(
                    Modifier
                        .width(6.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.backgroundAlt),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(data.build.progressFraction())
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accent),
                    )
                }
            } else {
                Box(Modifier.weight(1f))
            }
            MCUHomeIconButton(
                icon = MCUHomeIcons.dockBottom,
                contentDescription = "Dock the panel at the bottom",
                onClick = { actions.onLayout(layout.dockedTo(PanelDock.Bottom)) },
            )
            MCUHomeIconButton(
                icon = MCUHomeIcons.dockRight,
                contentDescription = "Dock the panel at the right",
                onClick = { actions.onLayout(layout.dockedTo(PanelDock.Right)) },
            )
        }
    }
}
