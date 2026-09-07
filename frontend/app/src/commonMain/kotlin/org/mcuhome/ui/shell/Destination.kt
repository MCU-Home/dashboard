// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The places the top navigation leads to. The route is what appears in
 * the browser address bar, so it is part of the interface's contract with
 * the user and does not change casually.
 */
enum class Destination(val route: String, val label: String) {
    Devices("devices", "Devices"),
    Configs("configs", "Configs"),
    Secrets("secrets", "Secrets"),
    Project("project", "Project"),
    ;

    companion object {
        val start: Destination = Devices
    }
}

/**
 * One device's own screen, below the Devices entry in the address bar:
 * `devices/kitchen-sensor`.
 *
 * It is a route with a value in it rather than a plain string, so the
 * device's name travels as an argument the destination reads back
 * instead of as a substring anyone has to parse. The serial name is what
 * the address bar shows, which is why it is stated rather than left to
 * the class's package.
 */
@Serializable
@SerialName("devices")
data class DeviceRoute(val name: String)

/**
 * The editor spike, reachable by typing its route but deliberately absent
 * from the navigation. It exists to try the editor out on a real browser
 * and is removed once the device screen carries the finished editor.
 */
const val SPIKE_ROUTE: String = "spike"
