// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

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
