// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.transportLabel
import org.mcuhome.ui.shell.WindowSizeClass

/**
 * The columns the device table draws at a given window size.
 *
 * Narrowing the table is a matter of leaving columns out, not of making
 * every column thinner: six columns squeezed into a tablet's width would
 * be six columns nobody can read. Signed goes first because the Build
 * cell says the same thing in words as soon as the column is gone, and
 * Network goes with it because it is the one column that says nothing
 * about the state of the device.
 */
fun deviceColumns(sizeClass: WindowSizeClass): List<DeviceColumn> = when (sizeClass) {
    WindowSizeClass.Expanded -> DeviceColumn.entries
    else -> listOf(DeviceColumn.Name, DeviceColumn.Board, DeviceColumn.Config, DeviceColumn.Build)
}

/**
 * The one thing a device row says about itself where there is room for
 * exactly one thing: the phone's list.
 *
 * The order is what a user would want to be told first. Something broken
 * comes before something happening, something happening before something
 * merely worth knowing, and the two states of a finished build come last
 * because they are the ones that need no action.
 */
enum class DeviceRowState {
    Errors,
    Building,
    Failed,
    Warnings,
    NotBuilt,
    Unsigned,
    Signed,

    /** Nothing has looked at the device yet. */
    Unchecked,
}

/** Which of the states above a device is in. */
fun deviceRowState(device: DeviceSummary): DeviceRowState = when {
    device.config.state == ConfigState.Errors -> DeviceRowState.Errors
    device.build.state == BuildState.Building -> DeviceRowState.Building
    device.build.state == BuildState.Failed -> DeviceRowState.Failed
    device.config.state == ConfigState.Warnings -> DeviceRowState.Warnings
    device.build.state == BuildState.NeverBuilt -> DeviceRowState.NotBuilt
    device.signed == SignedState.Unsigned -> DeviceRowState.Unsigned
    device.signed == SignedState.Signed -> DeviceRowState.Signed
    else -> DeviceRowState.Unchecked
}

/** What the pill of a phone's device row says. */
fun deviceRowLabel(device: DeviceSummary): String = when (deviceRowState(device)) {
    DeviceRowState.Errors -> countLabel(device.config.errorCount, "error")
    DeviceRowState.Building -> "building"
    DeviceRowState.Failed -> "build failed"
    DeviceRowState.Warnings -> countLabel(device.config.warningCount, "warning")
    DeviceRowState.NotBuilt -> "not built"
    DeviceRowState.Unsigned -> "unsigned"
    DeviceRowState.Signed -> "signed"
    DeviceRowState.Unchecked -> "not checked"
}

/** The colour that pill carries. */
fun deviceRowTone(state: DeviceRowState): PillTone = when (state) {
    DeviceRowState.Errors, DeviceRowState.Failed -> PillTone.Error
    DeviceRowState.Building -> PillTone.Accent
    DeviceRowState.Warnings, DeviceRowState.Unsigned -> PillTone.Warning
    DeviceRowState.Signed -> PillTone.Success
    DeviceRowState.NotBuilt, DeviceRowState.Unchecked -> PillTone.Neutral
}

/** How a device reaches the network, as the table's Network column and a phone's row say it. */
fun networkLabel(device: DeviceSummary): String {
    val transport = transportLabel(device.network.transport)
    val role = device.network.threadRole?.name?.uppercase()
    return listOfNotNull(transport, role).joinToString(" · ")
}

/**
 * A board as a phone's row names it: the board itself, without the
 * revision and the core behind the slashes.
 *
 * The full target — `nrf7002dk/nrf5340/cpuapp` — is what the build needs
 * and is twice as wide as a phone's row has to spare. Which board it is
 * sits in front of the first slash; the rest is the same for every device
 * that uses it.
 */
fun shortBoard(board: String): String = board.substringBefore('/')
