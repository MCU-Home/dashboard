// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.job

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobState
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.component.AnchoredPopover
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.TextAction
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.rememberNowEpochMillis

/** How long one half of the running dot's pulse takes, in milliseconds. */
private const val PULSE_MILLIS = 900

private val ChipShape = RoundedCornerShape(14.dp)
private val PopoverWidth = 420.dp

/**
 * The jobs chip in the top bar, and the popover it opens.
 *
 * It is the one place that answers "what is this thing doing" from
 * anywhere in the interface: a build keeps running while the user moves
 * between screens, and the chip is how it stays reachable. The dot pulses
 * only while something is actually running.
 */
@Composable
fun JobsChip(
    jobs: List<Job>,
    onOpenDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    val colors = MCUHomeTheme.colors
    var open by remember { mutableStateOf(false) }
    val running = jobs.count { it.state == JobState.Running }
    val offsetY = with(LocalDensity.current) { 8.dp.roundToPx() }
    val offsetX = with(LocalDensity.current) { 16.dp.roundToPx() }

    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(ChipShape)
                .background(if (running > 0) colors.accentTint else colors.backgroundAlt)
                .border(
                    width = 1.dp,
                    color = if (running > 0) colors.accentTintBorder else colors.border,
                    shape = ChipShape,
                )
                .handCursor().clickable { open = !open }
                .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StateDot(color = if (running > 0) colors.accent else colors.pinGray, pulsing = running > 0)
            Text(
                text = if (running > 0) "$running running" else "no jobs",
                color = if (running > 0) colors.accentOnTint else colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
            Icon(
                imageVector = MCUHomeIcons.chevronDown,
                contentDescription = null,
                tint = if (running > 0) colors.accentOnTint else colors.muted,
                modifier = Modifier.size(14.dp),
            )
        }

        if (open) {
            AnchoredPopover(
                onDismissRequest = { open = false },
                modifier = Modifier.width(PopoverWidth),
                offset = IntOffset(offsetX, offsetY),
            ) {
                JobsPopoverContent(
                    jobs = orderedJobs(jobs),
                    onCancel = { id -> scope.launch { runCatching { api.job.cancel(id) }.onApiFailure() } },
                    onClearFinished = { scope.launch { runCatching { api.job.clearFinished() }.onApiFailure() } },
                    onOpenDevice = { device ->
                        open = false
                        onOpenDevice(device)
                    },
                )
            }
        }
    }
}

/**
 * A refusal from one of the popover's two commands is dropped on purpose.
 *
 * Both of them act on a list the popover is showing: a build that
 * finished while the pointer was on its way to the cancel is no longer
 * cancellable, and the event that says so is already arriving. There is
 * nothing for the user to do about it and nothing to correct.
 */
private fun <T> Result<T>.onApiFailure(): Result<T> = onFailure { failure ->
    if (failure !is ApiException) throw failure
}

@Composable
private fun JobsPopoverContent(
    jobs: List<Job>,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    onOpenDevice: (String) -> Unit,
) {
    val colors = MCUHomeTheme.colors
    val now by rememberNowEpochMillis()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Jobs",
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Box(Modifier.weight(1f))
        TextAction(text = "Clear finished", onClick = onClearFinished)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    if (jobs.isEmpty()) {
        Text(
            text = "Nothing has run yet.",
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
            modifier = Modifier.padding(all = 14.dp),
        )
    }
    jobs.forEachIndexed { index, job ->
        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        JobEntry(
            job = job,
            nowEpochMillis = now,
            onCancel = { onCancel(job.id) },
            onOpenDevice = { onOpenDevice(job.device) },
        )
    }
}

@Composable
private fun JobEntry(
    job: Job,
    nowEpochMillis: Long,
    onCancel: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    val running = job.state == JobState.Running
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StateDot(color = stateColor(job), pulsing = running, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = jobTitle(job),
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Text(
                text = jobSubtitle(job, nowEpochMillis),
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
            )
            if (running) {
                ProgressBar(fraction = job.progress?.let { it.done.toFloat() / it.total } ?: 0f)
            }
        }
        if (running) {
            MCUHomeIconButton(
                icon = MCUHomeIcons.close,
                contentDescription = "Cancel this job",
                onClick = onCancel,
                bordered = true,
            )
        }
        MCUHomeIconButton(
            icon = MCUHomeIcons.chevronRight,
            contentDescription = "Open ${job.device}",
            onClick = onOpenDevice,
            bordered = true,
        )
    }
}

/** The thin accent bar under a running job's two lines. */
@Composable
private fun ProgressBar(fraction: Float) {
    val colors = MCUHomeTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.border),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(colors.accent),
        )
    }
}

@Composable
private fun StateDot(
    color: Color,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "jobs-pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "jobs-dot-alpha",
    )
    Box(
        modifier
            .size(8.dp)
            .alpha(if (pulsing) alpha else 1f)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun stateColor(job: Job): Color {
    val colors = MCUHomeTheme.colors
    return when (job.state) {
        JobState.Running -> colors.accent
        JobState.Finished -> colors.success
        JobState.Failed -> colors.error
        JobState.Cancelled -> colors.pinGray
    }
}
