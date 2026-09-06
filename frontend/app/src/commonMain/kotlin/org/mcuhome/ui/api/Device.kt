// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of the device table.
 *
 * Everything the table draws without opening a device: the two names, the
 * board, and the three state columns. The states are computed by the
 * server — the interface must not have to re-derive "unsigned" from a
 * build report.
 */
@Serializable
data class DeviceSummary(
    val name: String,
    val friendlyName: String,
    val board: String,
    val config: ConfigStatus,
    val build: BuildStatus,
    val signed: SignedState,
    val network: NetworkInfo,
)

/** The Config column: valid, or how many problems there are. */
@Serializable
data class ConfigStatus(val state: ConfigState, val errorCount: Int = 0, val warningCount: Int = 0)

/** The four states the Config pill shows. */
@Serializable
enum class ConfigState {
    /** Checked, nothing wrong. */
    @SerialName("valid")
    Valid,

    @SerialName("errors")
    Errors,

    @SerialName("warnings")
    Warnings,

    /** Not checked since the file last changed. */
    @SerialName("unknown")
    Unknown,
}

/** The Build column: what happened last, or what is happening now. */
@Serializable
data class BuildStatus(
    val state: BuildState,
    val method: BuildMethod? = null,
    val finishedAtEpochMillis: Long? = null,
    /** Set while [state] is [BuildState.Building]: the running build's id. */
    val buildId: String? = null,
    /** Set while [state] is [BuildState.Building]: compile progress. */
    val progress: Progress? = null,
)

/** The states a device's firmware can be in. */
@Serializable
enum class BuildState {
    @SerialName("never_built")
    NeverBuilt,

    @SerialName("building")
    Building,

    @SerialName("built")
    Built,

    @SerialName("failed")
    Failed,
}

/** The Signed column. [SignedState.Unknown] is the dash a never-built device shows. */
@Serializable
enum class SignedState {
    @SerialName("signed")
    Signed,

    @SerialName("unsigned")
    Unsigned,

    @SerialName("unknown")
    Unknown,
}

/** How far along a counted piece of work is — compile steps, mostly. */
@Serializable
data class Progress(val done: Int, val total: Int)

/**
 * The Network column: the transport the device joins and, for Thread, the
 * device type within it.
 */
@Serializable
data class NetworkInfo(val transport: NetworkTransport, val threadRole: ThreadRole? = null)

/** The link layer a device uses. */
@Serializable
enum class NetworkTransport {
    @SerialName("thread")
    Thread,

    @SerialName("wifi")
    WiFi,

    @SerialName("ethernet")
    Ethernet,
}

/** Full or minimal Thread device — what the design shows as "FTD" and "MTD". */
@Serializable
enum class ThreadRole {
    @SerialName("ftd")
    Ftd,

    @SerialName("mtd")
    Mtd,
}

/**
 * Everything the device page needs in one answer: the row it came from,
 * the file it edits, and the state its rail shows.
 *
 * [revision] identifies the version of [yaml] that was read. Handing it
 * back to `DeviceApi.save` is what lets the server notice that the file
 * changed underneath the editor.
 */
@Serializable
data class DeviceDetail(
    val summary: DeviceSummary,
    val path: String,
    val yaml: String,
    val revision: String,
    val includes: List<String> = emptyList(),
    val resolvedSecretCount: Int = 0,
    val diagnostics: List<Diagnostic> = emptyList(),
    val lastGoodBuild: BuildSummary? = null,
    val artifacts: List<ArtifactInfo> = emptyList(),
    val pairing: PairingSummary? = null,
)

/** What the rail says about the last build that produced artifacts. */
@Serializable
data class BuildSummary(
    val buildId: String,
    val method: BuildMethod,
    val mode: String? = null,
    val image: String? = null,
    val finishedAtEpochMillis: Long,
    val signed: Boolean,
)

/**
 * The answer to a write: either it landed, or the file had moved on.
 *
 * A conflict carries the current text as well as the current revision, so
 * the interface can show the difference without a second round trip.
 */
@Serializable
sealed interface SaveResult {
    @Serializable
    @SerialName("saved")
    data class Saved(val revision: String) : SaveResult

    @Serializable
    @SerialName("conflict")
    data class Conflict(val currentRevision: String, val currentText: String) : SaveResult
}

/** What the New device dialog collected. */
@Serializable
data class NewDeviceRequest(
    val name: String,
    val board: String,
    val friendlyName: String? = null,
    val starter: Starter = Starter.Minimal,
)

/** The "Start from" choice of the New device dialog. */
@Serializable
sealed interface Starter {
    /** The commented example the command line writes. */
    @Serializable
    @SerialName("minimal")
    data object Minimal : Starter

    @Serializable
    @SerialName("sensor-node")
    data object SensorNode : Starter

    @Serializable
    @SerialName("light")
    data object Light : Starter

    /** Copy another device's configuration, board line and names replaced. */
    @Serializable
    @SerialName("copy-of")
    data class CopyOf(val device: String) : Starter
}

/** What MCUHome can build for, and what it cannot build for yet. */
@Serializable
data class BoardRegistry(val registryVersion: String, val boards: List<BoardInfo> = emptyList())

/**
 * One board the New device dialog offers.
 *
 * A board that is [planned] is listed with its [plannedReason] rather than
 * hidden: a picker that says why something is missing is a better answer
 * than a short list with no explanation.
 */
@Serializable
data class BoardInfo(
    val target: String,
    val displayName: String,
    val vendor: String,
    val transports: List<NetworkTransport> = emptyList(),
    val planned: Boolean = false,
    val plannedReason: String? = null,
)

/** Stages one to three run out: the canonical device model, as JSON text. */
@Serializable
data class ResolvedModel(val device: String, val modelVersion: String, val json: String)
