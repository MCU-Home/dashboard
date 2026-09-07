// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import org.mcuhome.ui.api.BuildChanged
import org.mcuhome.ui.api.BuildEvent
import org.mcuhome.ui.api.BuildOutputAppended
import org.mcuhome.ui.api.BuildRunState
import org.mcuhome.ui.api.BuildSnapshot
import org.mcuhome.ui.api.BuildStage
import org.mcuhome.ui.api.OutputLine
import org.mcuhome.ui.api.Progress
import org.mcuhome.ui.api.StageState

/** How many output lines the Build tab keeps; older ones scroll out of memory. */
private const val OUTPUT_LIMIT = 2000

/**
 * A build as the Build tab shows it: the last snapshot the server sent
 * and the output it has printed so far.
 *
 * The stream is folded into this one value rather than read directly by
 * the screen, so a screen that attaches halfway through a build renders
 * exactly what one that watched from the start renders — the stream
 * replays, and replay and live events go through the same reduction.
 */
data class BuildRun(val snapshot: BuildSnapshot? = null, val lines: List<OutputLine> = emptyList()) {
    val running: Boolean get() = snapshot?.state == BuildRunState.Running || snapshot?.state == BuildRunState.Queued

    fun applied(event: BuildEvent): BuildRun = when (event) {
        is BuildChanged -> copy(snapshot = event.snapshot)
        is BuildOutputAppended -> copy(lines = (lines + event.lines).takeLast(OUTPUT_LIMIT))
    }

    /** How far the whole build has come, as a fraction for the progress bar. */
    fun progressFraction(): Float {
        val stages = snapshot?.stages ?: return 0f
        if (stages.isEmpty()) return 0f
        val done = stages.count { it.state == StageState.Done || it.state == StageState.Skipped }
        val running = stages.firstOrNull { it.state == StageState.Running }
        val within = running?.progress?.let { it.done.toFloat() / it.total.coerceAtLeast(1) } ?: 0f
        return ((done + within) / stages.size).coerceIn(0f, 1f)
    }

    /** The counted progress of the stage that is running, for the minimized bar. */
    fun runningProgress(): Progress? = snapshot?.stages?.firstOrNull { it.state == StageState.Running }?.progress
}

/**
 * A stage as the stage row labels it: "compile 142 / 380" while it counts
 * steps, "configure 6.1s" once it is over, the bare name before it starts.
 */
fun stageLabel(
    stage: BuildStage,
    progress: Progress?,
    durationMillis: Long?,
): String {
    val name = stage.name.lowercase()
    return when {
        progress != null -> "$name ${progress.done} / ${progress.total}"
        durationMillis != null -> "$name ${formatStageDuration(durationMillis)}"
        else -> name
    }
}

private const val MILLIS_PER_SECOND = 1000
private const val TENTHS_PER_SECOND = 10

/** A stage's duration as the stage row writes it: "0.4s", "6.1s", "12s". */
fun formatStageDuration(millis: Long): String {
    if (millis >= TENTHS_PER_SECOND * MILLIS_PER_SECOND) return "${millis / MILLIS_PER_SECOND}s"
    val tenths = (millis + MILLIS_PER_SECOND / (2 * TENTHS_PER_SECOND)) / (MILLIS_PER_SECOND / TENTHS_PER_SECOND)
    return "${tenths / TENTHS_PER_SECOND}.${tenths % TENTHS_PER_SECOND}s"
}
