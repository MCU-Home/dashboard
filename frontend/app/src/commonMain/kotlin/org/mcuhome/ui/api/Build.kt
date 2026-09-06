// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a build runs. The two methods the builder offers. */
@Serializable
enum class BuildMethod {
    @SerialName("local")
    Local,

    @SerialName("remote")
    Remote,
}

/**
 * The five steps of a build, in the order the stage row draws them.
 *
 * `sign` is a stage of the build rather than a separate operation because
 * that is how a build produces a signed image; signing an existing build
 * afterwards is `BuildApi.sign`, which runs this stage alone.
 */
@Serializable
enum class BuildStage {
    @SerialName("generate")
    Generate,

    @SerialName("configure")
    Configure,

    @SerialName("compile")
    Compile,

    @SerialName("link")
    Link,

    @SerialName("sign")
    Sign,
}

/** How far one [BuildStage] has got. */
@Serializable
data class StageStatus(
    val stage: BuildStage,
    val state: StageState,
    val durationMillis: Long? = null,
    /** Only the compile stage counts steps; the others are one step long. */
    val progress: Progress? = null,
)

/** The states a stage passes through. */
@Serializable
enum class StageState {
    @SerialName("pending")
    Pending,

    @SerialName("running")
    Running,

    @SerialName("done")
    Done,

    @SerialName("failed")
    Failed,

    /** Not run: an unsigned build skips signing. */
    @SerialName("skipped")
    Skipped,
}

/**
 * Everything about one build at one moment.
 *
 * The same object answers `build/status` and travels in every
 * `build_changed` event, so a screen that arrives late renders exactly
 * what a screen that watched from the start renders.
 */
@Serializable
data class BuildSnapshot(
    val buildId: String,
    val device: String,
    val method: BuildMethod,
    val state: BuildRunState,
    val stages: List<StageStatus> = emptyList(),
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    /** The container reference or build-server address the work ran on. */
    val image: String? = null,
    /** How the local method executes the work: `container` or `subprocess`. */
    val mode: String? = null,
    val parallelJobs: Int? = null,
    val artifacts: List<ArtifactInfo> = emptyList(),
    /** One sentence about how it ended; null while it is running. */
    val message: String? = null,
) {
    /** The stage the row highlights: the running one, or the last one reached. */
    val currentStage: BuildStage?
        get() = stages.lastOrNull { it.state == StageState.Running }?.stage
            ?: stages.lastOrNull { it.state != StageState.Pending }?.stage
}

/** Whether a build is waiting, working, or over — and how it ended. */
@Serializable
enum class BuildRunState {
    @SerialName("queued")
    Queued,

    @SerialName("running")
    Running,

    @SerialName("succeeded")
    Succeeded,

    @SerialName("failed")
    Failed,

    @SerialName("cancelled")
    Cancelled,
}

/** One line of build output, as the Build tab prints it. */
@Serializable
data class OutputLine(val timestampEpochMillis: Long, val text: String, val level: OutputLevel = OutputLevel.Plain)

/** How a build output line is coloured. */
@Serializable
enum class OutputLevel {
    @SerialName("plain")
    Plain,

    @SerialName("warning")
    Warning,

    @SerialName("error")
    Error,
}

/**
 * One file a build produced.
 *
 * The four fields are the builder's own artifact record — the directory
 * the path is relative to, that path, what the file is for, and the hash
 * its producer measured — plus the size, which only a list on a screen
 * needs.
 */
@Serializable
data class ArtifactInfo(
    val path: String,
    val role: String,
    val sha256: String,
    val sizeBytes: Long,
    val root: String = "",
) {
    /** The last path segment: what a download offers as the file name. */
    val fileName: String get() = path.substringAfterLast('/')
}

/**
 * Where to fetch an artifact's bytes.
 *
 * Artifacts do not travel over the command channel: the answer is a URL
 * the browser downloads with an ordinary request, which is what lets a
 * multi-megabyte image stream instead of being buffered as a message.
 */
@Serializable
data class ArtifactDownload(val url: String, val fileName: String, val sizeBytes: Long)
