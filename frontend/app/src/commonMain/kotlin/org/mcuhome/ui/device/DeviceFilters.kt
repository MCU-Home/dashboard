// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.SignedState

/** The columns of the device table, in the order the table draws them. */
enum class DeviceColumn(val label: String) {
    Name("Name"),
    Board("Board"),
    Config("Config"),
    Build("Build"),
    Signed("Signed"),
    Network("Network"),
}

/**
 * How the table is sorted. Clicking the column that is already sorted
 * turns it around; clicking another one sorts by that column upwards,
 * which is what every table the user has ever used does.
 */
data class DeviceSort(val column: DeviceColumn = DeviceColumn.Name, val ascending: Boolean = true) {
    fun clicked(column: DeviceColumn): DeviceSort =
        if (column == this.column) copy(ascending = !ascending) else DeviceSort(column, ascending = true)
}

/**
 * The four choices of the segmented filter.
 *
 * Each one answers a question the user actually has: what is broken, what
 * has never been built, and what has been built but not signed. A device
 * that was never built has no image at all, so it is not "unsigned" —
 * that column shows a dash for it, and this filter leaves it out.
 */
enum class DeviceFilterMode(val label: String) {
    All("All"),
    Errors("Errors"),
    NotBuilt("Not built"),
    Unsigned("Unsigned"),
}

/** Whether a device belongs in the table under this filter. */
fun DeviceFilterMode.accepts(device: DeviceSummary): Boolean = when (this) {
    DeviceFilterMode.All -> true
    DeviceFilterMode.Errors -> device.config.state == ConfigState.Errors || device.build.state == BuildState.Failed
    DeviceFilterMode.NotBuilt -> device.build.state == BuildState.NeverBuilt
    DeviceFilterMode.Unsigned -> device.signed == SignedState.Unsigned
}

/**
 * Whether a device matches what was typed into the filter field.
 *
 * The three columns a user searches by are the two names and the board;
 * the case is ignored, and a blank query matches everything.
 */
fun matchesQuery(device: DeviceSummary, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return device.name.contains(needle, ignoreCase = true) ||
        device.friendlyName.contains(needle, ignoreCase = true) ||
        device.board.contains(needle, ignoreCase = true)
}

/**
 * The order one column sorts the table in, upwards.
 *
 * The state columns have no natural order of their own, so each one is
 * given the order that puts what needs attention first: errors before
 * warnings before valid, a running build before a finished one, unsigned
 * before signed. Downwards is the same comparator reversed, and the name
 * decides between two rows that are otherwise equal.
 */
fun deviceComparator(sort: DeviceSort): Comparator<DeviceSummary> {
    val byColumn: Comparator<DeviceSummary> = when (sort.column) {
        DeviceColumn.Name -> compareBy { it.name }
        DeviceColumn.Board -> compareBy { it.board }
        DeviceColumn.Config -> compareBy { configRank(it) }
        DeviceColumn.Build -> compareBy({ buildRank(it) }, { -(it.build.finishedAtEpochMillis ?: 0L) })
        DeviceColumn.Signed -> compareBy { signedRank(it) }
        DeviceColumn.Network -> compareBy({ it.network.transport.ordinal }, { it.network.threadRole?.ordinal ?: -1 })
    }
    val withName = byColumn.thenBy { it.name }
    return if (sort.ascending) withName else withName.reversed()
}

/** The table's contents: what is left after the two filters, in the order the header says. */
fun visibleDevices(
    devices: List<DeviceSummary>,
    query: String,
    mode: DeviceFilterMode,
    sort: DeviceSort,
): List<DeviceSummary> = devices
    .filter { mode.accepts(it) && matchesQuery(it, query) }
    .sortedWith(deviceComparator(sort))

/**
 * The order each state column sorts in, written as the order itself
 * rather than as a table of numbers: a row's rank is its state's place in
 * the list, and moving a state is moving it here.
 */
private val CONFIG_ORDER = listOf(ConfigState.Errors, ConfigState.Warnings, ConfigState.Unknown, ConfigState.Valid)

private val BUILD_ORDER =
    listOf(BuildState.Building, BuildState.Failed, BuildState.Built, BuildState.NeverBuilt)

private val SIGNED_ORDER = listOf(SignedState.Unsigned, SignedState.Unknown, SignedState.Signed)

private fun configRank(device: DeviceSummary): Int = CONFIG_ORDER.indexOf(device.config.state)

private fun buildRank(device: DeviceSummary): Int = BUILD_ORDER.indexOf(device.build.state)

private fun signedRank(device: DeviceSummary): Int = SIGNED_ORDER.indexOf(device.signed)
