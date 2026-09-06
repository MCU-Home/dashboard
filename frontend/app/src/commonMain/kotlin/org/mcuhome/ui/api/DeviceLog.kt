// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One line of a running device's log, as the Device log tab prints it. */
@Serializable
data class LogLine(val timestampEpochMillis: Long, val level: LogLevel, val module: String, val message: String)

/** The severity a firmware log line carries. */
@Serializable
enum class LogLevel {
    @SerialName("error")
    Error,

    @SerialName("warning")
    Warning,

    @SerialName("info")
    Info,

    @SerialName("debug")
    Debug,
}

/** Where a device log is being read from — the footer of the Device log tab. */
@Serializable
data class LogTransport(val port: String, val baud: Int)
