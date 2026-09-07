// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.panel.BuildRun
import org.mcuhome.ui.theme.MCUHomeTheme

/** The width the design gives the status rail. */
val RailWidth = 260.dp

/** What is left of the rail when it is collapsed: an icon strip. */
val CollapsedRailWidth = 44.dp

/** What the rail can ask the device page to do. */
@Immutable
data class DeviceRailActions(
    val onCollapse: () -> Unit = {},
    val onExpand: () -> Unit = {},
    val onDownload: (ArtifactInfo) -> Unit = {},
    val onShowPairing: () -> Unit = {},
    val onJumpToLine: (Int) -> Unit = {},
)

/**
 * Everything about the open device that is not its text: whether the
 * configuration checks out, what the last build did, what it produced,
 * whether the device can be commissioned, and what the validator has to
 * say.
 *
 * It sits directly beside the editor because every one of those answers
 * is about the file next to it, and it collapses to an icon strip when
 * the window has no room for the words.
 */
@Composable
fun DeviceStatusRail(
    detail: DeviceDetail,
    diagnostics: List<Diagnostic>,
    build: BuildRun,
    nowEpochMillis: Long,
    actions: DeviceRailActions,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    Column(
        modifier = modifier
            .width(RailWidth)
            .fillMaxHeight()
            .background(colors.surface)
            .leftBorder()
            .verticalScroll(rememberScrollState()),
    ) {
        ConfigSection(detail, actions.onCollapse)
        BuildSection(detail, build, nowEpochMillis)
        ArtifactsSection(detail.artifacts, actions.onDownload)
        PairingSection(detail, actions.onShowPairing)
        DiagnosticsSection(diagnostics, actions.onJumpToLine)
    }
}

/**
 * The rail with the words taken away: one icon per section, each with the
 * dot that says whether that section wants attention, and the chevron
 * that brings the words back.
 */
@Composable
fun CollapsedStatusRail(
    detail: DeviceDetail,
    diagnostics: List<Diagnostic>,
    build: BuildRun,
    actions: DeviceRailActions,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    Column(
        modifier = modifier
            .width(CollapsedRailWidth)
            .fillMaxHeight()
            .background(colors.surface)
            .leftBorder()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MCUHomeIconButton(
            icon = MCUHomeIcons.chevronLeft,
            contentDescription = "Show the status rail",
            onClick = actions.onExpand,
        )
        val config = detail.summary.config
        StripIcon(
            icon = if (config.state == ConfigState.Errors) MCUHomeIcons.errorCircle else MCUHomeIcons.check,
            tint = when (config.state) {
                ConfigState.Errors -> colors.error
                ConfigState.Warnings -> colors.warning
                else -> colors.success
            },
            description = "Configuration state",
        )
        StripIcon(
            icon = MCUHomeIcons.hammer,
            tint = colors.muted,
            description = "Build state",
            dot = when {
                build.running || detail.summary.build.state == BuildState.Building -> colors.accent
                detail.summary.build.state == BuildState.Failed -> colors.error
                detail.summary.build.state == BuildState.Built -> colors.success
                else -> null
            },
        )
        StripIcon(
            icon = MCUHomeIcons.download,
            tint = colors.muted,
            description = "Artifacts of the last good build",
            onClick = if (detail.artifacts.isEmpty()) null else actions.onExpand,
        )
        StripIcon(
            icon = MCUHomeIcons.qr,
            tint = colors.muted,
            description = "Matter pairing",
            onClick = if (detail.pairing?.present == true) actions.onShowPairing else null,
        )
        StripIcon(
            icon = MCUHomeIcons.warningTriangle,
            tint = colors.muted,
            description = "Diagnostics",
            dot = diagnostics.firstOrNull()?.let { first ->
                if (diagnostics.any { it.severity == DiagnosticSeverity.Error }) {
                    colors.error
                } else if (first.severity == DiagnosticSeverity.Warning) {
                    colors.warning
                } else {
                    colors.info
                }
            },
        )
    }
}

/** One icon of the collapsed rail, with the dot that carries its state. */
@Composable
private fun StripIcon(
    icon: ImageVector,
    tint: Color,
    description: String,
    dot: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Box {
        MCUHomeIconButton(
            icon = icon,
            contentDescription = description,
            onClick = onClick ?: {},
            tint = tint,
        )
        if (dot != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
    }
}

/** The line that separates the rail from the editor beside it. */
@Composable
private fun Modifier.leftBorder(): Modifier {
    val colors = MCUHomeTheme.colors
    return drawBehind {
        val stroke = 1.dp.toPx()
        drawLine(
            color = colors.border,
            start = Offset(stroke / 2f, 0f),
            end = Offset(stroke / 2f, size.height),
            strokeWidth = stroke,
        )
    }
}
