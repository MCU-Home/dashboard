// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.SideRail
import org.mcuhome.ui.component.Tooltip
import org.mcuhome.ui.component.leftBorder
import org.mcuhome.ui.panel.BuildRun
import org.mcuhome.ui.theme.MCUHomeTheme
import kotlin.math.roundToInt

/** The width the design gives the status rail. */
val RailWidth = 260.dp

/** What is left of the rail when it is collapsed: an icon strip. */
val CollapsedRailWidth = 44.dp

/**
 * How long the rail waits for a block to report where it is before it
 * gives up scrolling to it. A block the device has nothing to say about —
 * artifacts before the first build — is not drawn at all, and the icon
 * that points at it must still open the rail rather than wait forever.
 */
private const val SCROLL_TARGET_TIMEOUT_MILLIS = 1000L

/** What the rail can ask the device page to do. */
@Immutable
data class DeviceRailActions(
    val onCollapse: () -> Unit = {},
    val onExpand: () -> Unit = {},
    /** Open the rail at one of its blocks: what an icon of the strip does. */
    val onOpenSection: (DeviceRailSection) -> Unit = {},
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
 *
 * [width] and [bordered] are what changes when the rail is not a column
 * beside the editor at all: on a phone it comes up as a sheet across the
 * whole screen, where a line down its left edge would be a line down the
 * middle of nothing.
 *
 * [scrollTo] is the block an icon of the collapsed strip asked for: the
 * rail scrolls it into view once it knows where it sits, and reports back
 * through [onScrolled] so the request is not repeated on every
 * recomposition.
 */
@Composable
fun DeviceStatusRail(
    detail: DeviceDetail,
    diagnostics: List<Diagnostic>,
    build: BuildRun,
    nowEpochMillis: Long,
    actions: DeviceRailActions,
    modifier: Modifier = Modifier,
    width: Dp = RailWidth,
    bordered: Boolean = true,
    collapsible: Boolean = true,
    scrollTo: DeviceRailSection? = null,
    onScrolled: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    // Where each block sits, in the coordinates of the page. Only the
    // distance between two of them is used, and that distance is the same
    // whether the rail is scrolled or not.
    val tops = remember { mutableStateMapOf<DeviceRailSection, Float>() }
    LaunchedEffect(scrollTo) {
        val section = scrollTo ?: return@LaunchedEffect
        withTimeoutOrNull(SCROLL_TARGET_TIMEOUT_MILLIS) {
            val offsets = snapshotFlow { tops[section] to tops[DeviceRailSection.Config] }
                .first { (target, first) -> target != null && first != null }
            val target = (offsets.first!! - offsets.second!!).roundToInt()
            scroll.animateScrollTo(target.coerceAtLeast(0))
        }
        onScrolled()
    }
    SideRail(width = width, modifier = modifier, scroll = scroll, bordered = bordered) {
        ConfigSection(
            detail = detail,
            onCollapse = actions.onCollapse,
            modifier = Modifier.railSectionTop(DeviceRailSection.Config, tops),
            collapsible = collapsible,
        )
        BuildSection(detail, build, nowEpochMillis, Modifier.railSectionTop(DeviceRailSection.Build, tops))
        ArtifactsSection(
            artifacts = detail.artifacts,
            onDownload = actions.onDownload,
            modifier = Modifier.railSectionTop(DeviceRailSection.Artifacts, tops),
        )
        PairingSection(detail, actions.onShowPairing, Modifier.railSectionTop(DeviceRailSection.Pairing, tops))
        DiagnosticsSection(
            diagnostics = diagnostics,
            onJumpToLine = actions.onJumpToLine,
            modifier = Modifier.railSectionTop(DeviceRailSection.Diagnostics, tops),
        )
    }
}

/** Reports where one block of the rail ended up, for the strip to scroll to. */
private fun Modifier.railSectionTop(section: DeviceRailSection, tops: MutableMap<DeviceRailSection, Float>): Modifier =
    onGloballyPositioned { tops[section] = it.positionInRoot().y }

/**
 * The rail with the words taken away: one icon per block, each with the
 * dot that says whether that block wants attention, the label it carries
 * on hover, and the chevron that brings the words back.
 *
 * Every icon opens the rail at its own block — the strip is a table of
 * contents, not decoration with three working entries.
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
    val config = detail.summary.config
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
        Tooltip(text = "Expand") {
            MCUHomeIconButton(
                icon = MCUHomeIcons.chevronLeft,
                contentDescription = "Show the status rail",
                onClick = actions.onExpand,
            )
        }
        StripIcon(
            section = DeviceRailSection.Config,
            state = configSectionState(config),
            icon = if (config.state == ConfigState.Errors) MCUHomeIcons.errorCircle else MCUHomeIcons.check,
            tint = when (config.state) {
                ConfigState.Errors -> colors.error
                ConfigState.Warnings -> colors.warning
                else -> colors.success
            },
            onClick = actions.onOpenSection,
        )
        StripIcon(
            section = DeviceRailSection.Build,
            state = buildSectionState(build, detail.summary.build),
            icon = MCUHomeIcons.hammer,
            tint = colors.muted,
            dot = when {
                build.running || detail.summary.build.state == BuildState.Building -> colors.accent
                detail.summary.build.state == BuildState.Failed -> colors.error
                detail.summary.build.state == BuildState.Built -> colors.success
                else -> null
            },
            onClick = actions.onOpenSection,
        )
        StripIcon(
            section = DeviceRailSection.Artifacts,
            state = artifactsSectionState(detail.artifacts.size),
            icon = MCUHomeIcons.download,
            tint = colors.muted,
            onClick = actions.onOpenSection,
        )
        StripIcon(
            section = DeviceRailSection.Pairing,
            state = pairingSectionState(detail.pairing),
            icon = MCUHomeIcons.qr,
            tint = colors.muted,
            onClick = actions.onOpenSection,
        )
        StripIcon(
            section = DeviceRailSection.Diagnostics,
            state = diagnosticsSectionState(diagnostics),
            icon = MCUHomeIcons.warningTriangle,
            tint = colors.muted,
            dot = diagnostics.firstOrNull()?.let { first ->
                if (diagnostics.any { it.severity == DiagnosticSeverity.Error }) {
                    colors.error
                } else if (first.severity == DiagnosticSeverity.Warning) {
                    colors.warning
                } else {
                    colors.info
                }
            },
            onClick = actions.onOpenSection,
        )
    }
}

/** One icon of the collapsed rail, with the dot and the label it carries. */
@Composable
private fun StripIcon(
    section: DeviceRailSection,
    state: String,
    icon: ImageVector,
    tint: Color,
    onClick: (DeviceRailSection) -> Unit,
    dot: Color? = null,
) {
    val label = railSectionLabel(section, state)
    Tooltip(text = label) {
        Box {
            MCUHomeIconButton(
                icon = icon,
                contentDescription = label,
                onClick = { onClick(section) },
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
}
