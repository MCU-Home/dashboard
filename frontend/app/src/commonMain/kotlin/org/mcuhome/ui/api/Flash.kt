// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The two ways firmware reaches a device.
 *
 * Both are declared and neither works yet: the workbench has a stub for
 * recovery flashing and nothing for over-the-air updates. The commands
 * exist so the screens can be built and so the answer is a clear "not
 * available yet, here is why" rather than a missing button.
 */
@Serializable
enum class FlashMode {
    /** MCUboot serial recovery over the board's USB port. */
    @SerialName("recovery")
    Recovery,

    /** A Matter over-the-air update; the device fetches it on its next check-in. */
    @SerialName("ota")
    Ota,
}

/** One serial port a recovery flash could use. */
@Serializable
data class SerialPort(val path: String, val description: String)

/** One image the Flash dialog offers, out of the last good build. */
@Serializable
data class FlashImage(
    val path: String,
    val fileName: String,
    val signed: Boolean,
    val sizeBytes: Long,
    val buildId: String,
    val builtAtEpochMillis: Long,
)

/** What the Flash dialog collected. */
@Serializable
data class FlashRequest(val device: String, val imagePath: String, val mode: FlashMode, val port: String? = null)

/** Which images and ports the Flash dialog can offer for one device. */
@Serializable
data class FlashOptions(
    val device: String,
    val images: List<FlashImage> = emptyList(),
    val ports: List<SerialPort> = emptyList(),
    /**
     * True when first-time setup has not been run on this board, which is
     * the warning the dialog shows before a recovery flash.
     */
    val firstTimeSetupNeeded: Boolean = false,
)
