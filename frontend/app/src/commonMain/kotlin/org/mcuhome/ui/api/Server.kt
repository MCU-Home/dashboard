// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who is on the other end and which project is open.
 *
 * The top bar shows [projectName]; the Project screen adds [projectId],
 * the short identifier that distinguishes two projects with the same
 * folder name.
 */
@Serializable
data class ServerInfo(
    val serverVersion: String,
    val workbenchVersion: String,
    val projectName: String,
    val projectId: String,
    val projectRoot: String,
    val capabilities: List<Capability> = emptyList(),
)

/**
 * One thing the server either can or cannot do.
 *
 * Declared rather than discovered: the interface offers flashing,
 * first-time setup and the device log on every screen that needs them and
 * uses this list to say up front that they are not there yet, instead of
 * letting the user find out by pressing the button.
 */
@Serializable
data class Capability(val name: String, val available: Boolean, val reason: String? = null)

/** Whether the interface currently has a live connection to the server. */
@Serializable
enum class ConnectionState {
    @SerialName("connecting")
    Connecting,

    @SerialName("connected")
    Connected,

    @SerialName("reconnecting")
    Reconnecting,

    @SerialName("disconnected")
    Disconnected,
}
