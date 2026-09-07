// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.DarkSchemeContent
import org.mcuhome.ui.theme.MCUHomeTheme

/** The height of the panel's tab row. */
private val PanelHeaderHeight = 44.dp

/**
 * The output panel: the build as it runs, the diagnostics, the device
 * log, the resolved model and the artifacts, behind five tabs.
 *
 * It is drawn in the dark scheme whatever scheme the window is in — the
 * design's one deliberate exception, and the reason build output and a
 * device log read like the terminal they come from.
 */
@Composable
fun OutputPanel(
    layout: PanelLayout,
    data: OutputPanelData,
    actions: OutputPanelActions,
    modifier: Modifier = Modifier,
) {
    DarkSchemeContent {
        val colors = MCUHomeTheme.colors
        Column(modifier.background(colors.background)) {
            PanelHeader(layout, data, actions)
            Box(Modifier.fillMaxSize()) {
                when (layout.tab) {
                    PanelTab.Build -> BuildTab(data.build, actions.onCancelBuild)
                    PanelTab.Diagnostics -> DiagnosticsTab(data.diagnostics, actions.onJumpToLine)
                    PanelTab.DeviceLog -> DeviceLogTab(data.logNotAvailable)
                    PanelTab.Model -> ModelTab(data.model)
                    PanelTab.Artifacts -> ArtifactsTab(data.artifacts, actions.onDownload)
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    layout: PanelLayout,
    data: OutputPanelData,
    actions: OutputPanelActions,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PanelHeaderHeight)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = colors.border,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tabs give way before the dock controls do: a narrow panel
        // must still be movable and closable, and a tab that is out of
        // sight is one scroll away.
        Row(
            modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelTab.entries.forEach { tab ->
                PanelTabItem(
                    tab = tab,
                    active = tab == layout.tab,
                    count = if (tab == PanelTab.Diagnostics) data.diagnostics.size else 0,
                    onClick = { actions.onLayout(layout.showing(tab)) },
                )
            }
        }
        DockControls(layout, actions)
    }
}

/** The dock toggle and the minimize button, in the panel's corner. */
@Composable
private fun DockControls(layout: PanelLayout, actions: OutputPanelActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        PanelDockToggle(layout, actions)
        PanelMinimizeButton(layout, actions)
    }
}

@Composable
private fun PanelTabItem(
    tab: PanelTab,
    active: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .handCursor().clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
            .drawBehind {
                if (active) {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = colors.accent,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tab == PanelTab.Build) {
            Icon(
                imageVector = MCUHomeIcons.hammer,
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
            fontSize = 13.sp,
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
