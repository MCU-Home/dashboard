// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.job

import org.mcuhome.ui.api.BuildStage
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobKind
import org.mcuhome.ui.api.JobState
import org.mcuhome.ui.time.formatDuration
import org.mcuhome.ui.time.formatTimeOfDay
import org.mcuhome.ui.time.formatTimestampCompact

/** A job that took less than this is not worth stating a duration for. */
private const val SHORTEST_STATED_DURATION_MILLIS = 1_000L

/**
 * The order the jobs popover lists its entries in: what is still running
 * first, newest first within each group.
 *
 * A running job is what the user opened the popover for, so it stays at
 * the top however long it has been going; everything else is history and
 * reads newest first, like every log does.
 */
fun orderedJobs(jobs: List<Job>): List<Job> = jobs.sortedWith(
    compareBy<Job> { if (it.state == JobState.Running) 0 else 1 }
        .thenByDescending { it.finishedAtEpochMillis ?: it.startedAtEpochMillis }
        .thenBy { it.device },
)

/** What kind of work a job is, in the words the interface uses elsewhere. */
fun jobKindLabel(kind: JobKind): String = when (kind) {
    JobKind.Build -> "build"
    JobKind.Validate -> "validate"
    JobKind.Sign -> "sign"
    JobKind.Flash -> "flash"
    JobKind.FirstTimeSetup -> "first-time setup"
    JobKind.Clean -> "clean"
    JobKind.Pairing -> "pairing"
}

/** The first line of an entry: which device, and what is being done to it. */
fun jobTitle(job: Job): String = "${job.device} · ${jobKindLabel(job.kind)}"

/**
 * The second line: how far a running job has got, or how a finished one
 * ended and what it left behind.
 *
 * The parts are assembled from the job's own fields rather than sent as a
 * rendered sentence — only [Job.summary], the tail that is different for
 * every kind of work, comes from the server as text.
 */
fun jobSubtitle(job: Job, nowEpochMillis: Long): String {
    val parts = if (job.state == JobState.Running) {
        listOfNotNull(
            stagePart(job),
            job.method?.name?.lowercase(),
            "started ${formatTimeOfDay(job.startedAtEpochMillis)}",
        )
    } else {
        val finishedAt = job.finishedAtEpochMillis ?: job.startedAtEpochMillis
        val duration = finishedAt - job.startedAtEpochMillis
        listOfNotNull(
            "${endStateLabel(job.state)} ${formatTimestampCompact(finishedAt, nowEpochMillis)}",
            if (duration >= SHORTEST_STATED_DURATION_MILLIS) formatDuration(duration) else null,
            job.summary,
        )
    }
    return parts.joinToString(" · ")
}

/** How far a job has got: the stage it is in, with its counted progress where it has one. */
private fun stagePart(job: Job): String? {
    val stage = job.stage ?: return null
    val progress = job.progress ?: return stageLabel(stage)
    return "${stageLabel(stage)} ${progress.done} / ${progress.total}"
}

private fun stageLabel(stage: BuildStage): String = stage.name.lowercase()

private fun endStateLabel(state: JobState): String = when (state) {
    JobState.Finished -> "finished"
    JobState.Failed -> "failed"
    JobState.Cancelled -> "cancelled"
    JobState.Running -> "running"
}
