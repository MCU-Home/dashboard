// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something the server says happened, without being asked.
 *
 * Each class's `@SerialName` is the event name on the wire, and the wire
 * names follow the API's own convention: a noun and a past participle in
 * snake case. The topic an event is delivered on is the plural of its
 * noun — `devices`, `builds`, `jobs`, `configs` — which is what a client
 * subscribes to.
 *
 * Events carry the whole changed object rather than a patch. A screen that
 * connects late, or reconnects, then renders the same thing as one that
 * watched from the start, and no client has to keep a merge algorithm in
 * step with the server's.
 */
@Serializable
sealed interface ApiEvent

/** Events on the `devices` topic. */
@Serializable
sealed interface DeviceEvent : ApiEvent {
    val device: String
}

@Serializable
@SerialName("device_added")
data class DeviceAdded(val summary: DeviceSummary) : DeviceEvent {
    override val device: String get() = summary.name
}

@Serializable
@SerialName("device_changed")
data class DeviceChanged(val summary: DeviceSummary) : DeviceEvent {
    override val device: String get() = summary.name
}

@Serializable
@SerialName("device_removed")
data class DeviceRemoved(override val device: String) : DeviceEvent

/** Events on the `builds` topic. */
@Serializable
sealed interface BuildEvent : ApiEvent {
    val buildId: String
}

/** The build's state, stage or progress moved. */
@Serializable
@SerialName("build_changed")
data class BuildChanged(val snapshot: BuildSnapshot) : BuildEvent {
    override val buildId: String get() = snapshot.buildId
}

/** New output lines, in order, since the last one of these. */
@Serializable
@SerialName("build_output_appended")
data class BuildOutputAppended(override val buildId: String, val lines: List<OutputLine>) : BuildEvent

/** Events on the `jobs` topic. */
@Serializable
sealed interface JobEvent : ApiEvent

@Serializable
@SerialName("job_added")
data class JobAdded(val job: Job) : JobEvent

@Serializable
@SerialName("job_changed")
data class JobChanged(val job: Job) : JobEvent

/** The finished entries the user cleared out of the popover. */
@Serializable
@SerialName("jobs_cleared")
data class JobsCleared(val ids: List<String>) : JobEvent

/** Events on the `configs` topic. */
@Serializable
sealed interface ConfigEvent : ApiEvent {
    val fileName: String
}

@Serializable
@SerialName("config_changed")
data class ConfigChanged(override val fileName: String) : ConfigEvent

/**
 * The server could not keep up and threw events away.
 *
 * A client that sees this refetches what it is showing instead of trusting
 * its cache — the one honest answer to a gap in a stream.
 */
@Serializable
@SerialName("events_dropped")
data class EventsDropped(val count: Int) : ApiEvent
