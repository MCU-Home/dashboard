// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.BuildRunState
import org.mcuhome.ui.api.OutputLevel
import org.mcuhome.ui.api.OutputLine
import org.mcuhome.ui.api.StageState
import org.mcuhome.ui.api.StageStatus
import org.mcuhome.ui.component.TextAction
import org.mcuhome.ui.theme.MCUHomeColors
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.formatTimeOfDaySeconds

private val StageRowHeight = 38.dp
private val FooterHeight = 34.dp

/**
 * The build as it happens: which stage it is in, how far that stage has
 * come, everything it has printed, and — while it runs — the way to stop
 * it.
 */
@Composable
fun BuildTab(
    run: BuildRun,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    val snapshot = run.snapshot
    if (snapshot == null) {
        Box(modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Nothing has been built in this session. Press Build to start one; " +
                    "the last build's files are in the Artifacts tab.",
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        StageRow(run)
        OutputLines(run.lines, Modifier.weight(1f))
        BuildFooter(run, onCancel)
    }
}

@Composable
private fun StageRow(run: BuildRun) {
    val snapshot = run.snapshot ?: return
    Row(
        modifier = Modifier.fillMaxWidth().height(StageRowHeight).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        snapshot.stages.forEach { status ->
            Stage(status)
            if (status.state == StageState.Running) {
                ProgressBar(run.progressFraction(), Modifier.weight(1f))
            }
        }
        if (snapshot.stages.none { it.state == StageState.Running }) {
            ProgressBar(run.progressFraction(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stage(status: StageStatus) {
    val colors = MCUHomeTheme.colors
    val tint = colors.stageColor(status.state)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .then(
                    if (status.state == StageState.Pending) {
                        Modifier.border(1.dp, colors.muted, CircleShape)
                    } else {
                        Modifier.background(tint)
                    },
                ),
        )
        Text(
            text = stageLabel(status.stage, status.progress, status.durationMillis),
            color = if (status.state == StageState.Pending) colors.muted else colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.5.sp,
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.backgroundAlt),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.accent),
        )
    }
}

/**
 * The build's output, newest at the bottom.
 *
 * The view follows the end of the output as it arrives — which is what a
 * build log is read for — and stops following as soon as the user scrolls
 * away from it.
 */
@Composable
private fun OutputLines(lines: List<OutputLine>, modifier: Modifier = Modifier) {
    val colors = MCUHomeTheme.colors
    val listState = rememberLazyListState()
    val atEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= lines.lastIndex - 1 } ?: true
    LaunchedEffect(lines.size) {
        if (atEnd && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(lines) { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = formatTimeOfDaySeconds(line.timestampEpochMillis),
                    color = colors.muted,
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 12.sp,
                )
                Text(
                    text = line.text,
                    color = colors.outputColor(line.level),
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** What the build is: when it started, where it ran, and how to stop it. */
@Composable
private fun BuildFooter(run: BuildRun, onCancel: () -> Unit) {
    val colors = MCUHomeTheme.colors
    val snapshot = run.snapshot ?: return
    val method = listOfNotNull(snapshot.method.name.lowercase(), snapshot.mode, snapshot.image).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FooterHeight)
            .background(colors.backgroundAlt)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FooterText("started ${formatTimeOfDaySeconds(snapshot.startedAtEpochMillis)}")
        FooterText(method)
        snapshot.parallelJobs?.let { FooterText("jobs $it") }
        Box(Modifier.weight(1f))
        if (run.running) {
            TextAction(text = "Cancel", onClick = onCancel)
        } else {
            Text(
                text = snapshot.message ?: snapshot.state.name.lowercase(),
                color = if (snapshot.state == BuildRunState.Succeeded) colors.successOnTint else colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun FooterText(text: String) {
    Text(
        text = text,
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 12.sp,
    )
}

private fun MCUHomeColors.stageColor(state: StageState): Color = when (state) {
    StageState.Done -> success
    StageState.Running -> accent
    StageState.Failed -> error
    StageState.Skipped -> muted
    StageState.Pending -> muted
}

private fun MCUHomeColors.outputColor(level: OutputLevel): Color = when (level) {
    OutputLevel.Plain -> ink
    OutputLevel.Warning -> warning
    OutputLevel.Error -> error
}
