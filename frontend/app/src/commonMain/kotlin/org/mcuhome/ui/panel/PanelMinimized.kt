// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import org.mcuhome.ui.component.Tooltip
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.DarkSchemeContent
import org.mcuhome.ui.theme.MCUHomeTheme

private val ProgressBarWidth = 220.dp

private val EntryShape = RoundedCornerShape(6.dp)

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
 * The panel as a status bar across the bottom of the editor column: what
 * the build is doing, which tab is open, how many diagnostics there are,
 * and the way back to the full panel.
 *
 * Minimizing hides the output, not the state: every tab the open panel
 * has is here too, the one that was open stays marked, and a click on any
 * of them brings the panel back on that tab.
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
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PanelTab.entries.forEach { tab ->
                MinimizedEntry(
                    tab = tab,
                    active = tab == layout.tab,
                    count = if (tab == PanelTab.Diagnostics) data.diagnostics.size else 0,
                    onClick = { actions.onLayout(layout.showing(tab)) },
                )
                if (tab == PanelTab.Build) RunningBuild(data)
            }
            Box(Modifier.weight(1f))
            PanelDockToggle(layout, actions)
            PanelRestoreButton(layout, actions)
        }
    }
}

/** What the bar says about a build while it runs: the stage, and how far it is. */
@Composable
private fun RunningBuild(data: OutputPanelData) {
    val colors = MCUHomeTheme.colors
    val stage = data.build.snapshot?.stages?.firstOrNull { it.state == StageState.Running } ?: return
    Row(
        modifier = Modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
}

/** One tab of the bar: its name, its count, and whether it is the open one. */
@Composable
private fun MinimizedEntry(
    tab: PanelTab,
    active: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Tooltip(text = minimizedTabLabel(tab, count)) {
        Row(
            modifier = Modifier
                .clip(EntryShape)
                .then(if (active) Modifier.background(colors.backgroundAlt) else Modifier)
                .handCursor().clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (tab == PanelTab.Build) {
                Icon(
                    imageVector = tabIcon(tab),
                    contentDescription = null,
                    tint = if (active) colors.ink else colors.muted,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = tab.label,
                color = if (active) colors.ink else colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
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
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PanelRestoreButton(layout, actions)
            PanelTab.entries.forEach { tab ->
                val count = if (tab == PanelTab.Diagnostics) data.diagnostics.size else 0
                StripEntry(
                    tab = tab,
                    active = tab == layout.tab,
                    count = count,
                    warning = tab == PanelTab.Diagnostics && count > 0,
                    onClick = { actions.onLayout(layout.showing(tab)) },
                )
            }
            RunningBuildStrip(data)
            PanelDockToggle(layout, actions)
        }
    }
}

/** One tab of the strip: its icon, marked when it is the open one. */
@Composable
private fun StripEntry(
    tab: PanelTab,
    active: Boolean,
    count: Int,
    warning: Boolean,
    onClick: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Tooltip(text = minimizedTabLabel(tab, count)) {
        MCUHomeIconButton(
            icon = tabIcon(tab),
            contentDescription = minimizedTabLabel(tab, count),
            onClick = onClick,
            tint = when {
                warning -> colors.warning
                active -> colors.ink
                else -> colors.muted
            },
            modifier = if (active) Modifier.clip(EntryShape).background(colors.backgroundAlt) else Modifier,
        )
    }
}

/**
 * The strip's share of the column: the stage of a running build read from
 * top to bottom with its progress bar, or the empty space that pushes the
 * dock button to the bottom edge.
 */
@Composable
private fun ColumnScope.RunningBuildStrip(data: OutputPanelData) {
    val colors = MCUHomeTheme.colors
    val stage = data.build.snapshot?.stages?.firstOrNull { it.state == StageState.Running }
    if (stage == null) {
        Box(Modifier.weight(1f))
        return
    }
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
}

/**
 * The minimized panel on a phone: one line saying what the build is
 * doing, how many diagnostics are waiting, and that there is more behind
 * it.
 *
 * The bar of a desktop window carries all five tabs; a phone's width is
 * gone after two of them. What is left is the state itself, and the whole
 * bar is the button that brings the panel back — a 36 px line is a poor
 * target, a 36 px line across the screen is a good one.
 */
@Composable
fun PanelMinimizedPhoneBar(
    layout: PanelLayout,
    data: OutputPanelData,
    actions: OutputPanelActions,
    modifier: Modifier = Modifier,
) {
    DarkSchemeContent {
        val colors = MCUHomeTheme.colors
        val stage = data.build.snapshot?.stages?.firstOrNull { it.state == StageState.Running }
        val count = data.diagnostics.size
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(MinimizedBarHeight)
                .background(colors.background)
                .handCursor().clickable { actions.onLayout(layout.restored()) }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = tabIcon(layout.tab),
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stage?.let { stageLabel(it.stage, it.progress, it.durationMillis) } ?: layout.tab.label,
                color = if (stage != null) colors.accent else colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.5.sp,
                maxLines = 1,
            )
            if (stage != null) {
                Box(
                    Modifier
                        .weight(1f)
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
            } else {
                Box(Modifier.weight(1f))
            }
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
            PanelRestoreButton(layout, actions)
        }
    }
}
