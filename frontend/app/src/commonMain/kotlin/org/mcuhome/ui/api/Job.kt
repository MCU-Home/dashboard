// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of the jobs popover.
 *
 * Everything the interface starts that outlives a single request is a job:
 * builds, but also validating, signing and — once they exist — flashing
 * and first-time setup. That is what makes the chip a complete answer to
 * "what is this thing doing", and it is why a build that keeps running
 * while the user navigates is reachable from anywhere.
 *
 * The popover formats its two lines from these fields; no rendered string
 * is sent. [summary] is the one exception: the tail of a finished job's
 * line ("2 errors in main.yaml", the artifact names, the port that was
 * flashed) is the operation's own result and has no shape common to all
 * of them.
 */
@Serializable
data class Job(
    val id: String,
    val kind: JobKind,
    val device: String,
    val state: JobState,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val progress: Progress? = null,
    /** The build stage a running build is in — the "compile 142 / 380" part. */
    val stage: BuildStage? = null,
    val method: BuildMethod? = null,
    val summary: String? = null,
    /** Set for jobs that are builds, so the popover can jump to the output. */
    val buildId: String? = null,
)

/** What kind of work a [Job] is. */
@Serializable
enum class JobKind {
    @SerialName("build")
    Build,

    @SerialName("validate")
    Validate,

    @SerialName("sign")
    Sign,

    @SerialName("flash")
    Flash,

    @SerialName("first-time-setup")
    FirstTimeSetup,

    @SerialName("clean")
    Clean,

    @SerialName("pairing")
    Pairing,
}

/** Whether a job is still going, and how it ended if it is not. */
@Serializable
enum class JobState {
    @SerialName("running")
    Running,

    @SerialName("finished")
    Finished,

    @SerialName("failed")
    Failed,

    @SerialName("cancelled")
    Cancelled,
    ;

    val isFinished: Boolean get() = this != Running
}
