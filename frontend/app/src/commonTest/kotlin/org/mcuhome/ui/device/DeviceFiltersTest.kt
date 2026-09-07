// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.BuildStatus
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.ConfigStatus
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.NetworkInfo
import org.mcuhome.ui.api.NetworkTransport
import org.mcuhome.ui.api.Progress
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.api.ThreadRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 1_788_785_526_000L

/** The six devices of the sample project, as the table receives them. */
private val SAMPLE = listOf(
    device(
        name = "kitchen-sensor",
        friendlyName = "Kitchen Sensor",
        board = "nrf7002dk/nrf5340/cpuapp",
        build = BuildStatus(BuildState.Built, BuildMethod.Local, NOW - 660_000),
        signed = SignedState.Signed,
    ),
    device(
        name = "hallway-light",
        friendlyName = "Hallway Light",
        board = "nrf52840dk/nrf52840",
        config = ConfigStatus(ConfigState.Errors, errorCount = 2),
        network = NetworkInfo(NetworkTransport.Thread, ThreadRole.Ftd),
    ),
    device(
        name = "garage-door",
        friendlyName = "Garage Door",
        board = "esp32c6_devkitc/esp32c6/hpcore",
        config = ConfigStatus(ConfigState.Warnings, warningCount = 1),
        build = BuildStatus(BuildState.Building, BuildMethod.Local, buildId = "b-1", progress = Progress(142, 380)),
        network = NetworkInfo(NetworkTransport.WiFi),
    ),
    device(
        name = "bench-node",
        friendlyName = "Bench Node",
        board = "nrf7002dk/nrf5340/cpuapp",
        build = BuildStatus(BuildState.Built, BuildMethod.Remote, NOW - 67_500_000),
        signed = SignedState.Unsigned,
    ),
    device(
        name = "office-plug",
        friendlyName = "Office Plug",
        board = "nrf52840dk/nrf52840",
        config = ConfigStatus(ConfigState.Warnings, warningCount = 1),
        build = BuildStatus(BuildState.Built, BuildMethod.Local, NOW - 246_720_000),
        signed = SignedState.Signed,
        network = NetworkInfo(NetworkTransport.Thread, ThreadRole.Ftd),
    ),
    device(name = "balcony-climate", friendlyName = "Balcony Climate", board = "nrf7002dk/nrf5340/cpuapp"),
)

private fun device(
    name: String,
    friendlyName: String,
    board: String,
    config: ConfigStatus = ConfigStatus(ConfigState.Valid),
    build: BuildStatus = BuildStatus(BuildState.NeverBuilt),
    signed: SignedState = SignedState.Unknown,
    network: NetworkInfo = NetworkInfo(NetworkTransport.Thread, ThreadRole.Mtd),
) = DeviceSummary(name, friendlyName, board, config, build, signed, network)

private fun names(
    query: String = "",
    mode: DeviceFilterMode = DeviceFilterMode.All,
    sort: DeviceSort = DeviceSort(),
) = visibleDevices(SAMPLE, query, mode, sort).map { it.name }

/**
 * What the device table shows: which rows survive the two filters, and in
 * which order the header puts them.
 */
class DeviceFiltersTest {
    @Test
    fun theTableStartsSortedByNameUpwards() {
        assertEquals(DeviceColumn.Name, DeviceSort().column)
        assertTrue(DeviceSort().ascending)
        assertEquals(
            listOf("balcony-climate", "bench-node", "garage-door", "hallway-light", "kitchen-sensor", "office-plug"),
            names(),
        )
    }

    @Test
    fun clickingTheColumnThatIsSortedTurnsItAround() {
        val sort = DeviceSort().clicked(DeviceColumn.Name)
        assertFalse(sort.ascending)
        assertEquals(names().reversed(), names(sort = sort))
    }

    @Test
    fun clickingAnotherColumnSortsByItUpwards() {
        val sort = DeviceSort(DeviceColumn.Name, ascending = false).clicked(DeviceColumn.Board)
        assertEquals(DeviceColumn.Board, sort.column)
        assertTrue(sort.ascending)
    }

    @Test
    fun sortingByBoardGroupsTheSameBoardAndKeepsTheNameOrderInside() {
        assertEquals(
            listOf("garage-door", "hallway-light", "office-plug", "balcony-climate", "bench-node", "kitchen-sensor"),
            names(sort = DeviceSort(DeviceColumn.Board)),
        )
    }

    @Test
    fun sortingByConfigPutsWhatIsBrokenFirst() {
        assertEquals(
            listOf("hallway-light", "garage-door", "office-plug", "balcony-climate", "bench-node", "kitchen-sensor"),
            names(sort = DeviceSort(DeviceColumn.Config)),
        )
    }

    @Test
    fun sortingByBuildPutsTheRunningBuildFirstAndTheNewestFinishedNext() {
        assertEquals(
            listOf("garage-door", "kitchen-sensor", "bench-node", "office-plug", "balcony-climate", "hallway-light"),
            names(sort = DeviceSort(DeviceColumn.Build)),
        )
    }

    @Test
    fun sortingBySignedPutsUnsignedFirstAndSignedLast() {
        assertEquals(
            listOf("bench-node", "balcony-climate", "garage-door", "hallway-light", "kitchen-sensor", "office-plug"),
            names(sort = DeviceSort(DeviceColumn.Signed)),
        )
    }

    @Test
    fun sortingByNetworkGroupsTheTransportAndTheThreadRoleWithinIt() {
        assertEquals(
            listOf("hallway-light", "office-plug", "balcony-climate", "bench-node", "kitchen-sensor", "garage-door"),
            names(sort = DeviceSort(DeviceColumn.Network)),
        )
    }

    @Test
    fun theErrorsFilterKeepsConfigurationErrorsAndFailedBuilds() {
        assertEquals(listOf("hallway-light"), names(mode = DeviceFilterMode.Errors))

        val failed = SAMPLE.first { it.name == "office-plug" }
            .copy(config = ConfigStatus(ConfigState.Valid), build = BuildStatus(BuildState.Failed))
        assertTrue(DeviceFilterMode.Errors.accepts(failed))
    }

    @Test
    fun theNotBuiltFilterKeepsTheDevicesThatHaveNeverBeenBuilt() {
        assertEquals(listOf("balcony-climate", "hallway-light"), names(mode = DeviceFilterMode.NotBuilt))
    }

    @Test
    fun theUnsignedFilterLeavesOutDevicesThatHaveNoImageAtAll() {
        assertEquals(listOf("bench-node"), names(mode = DeviceFilterMode.Unsigned))
    }

    @Test
    fun theFilterFieldSearchesTheTwoNamesAndTheBoard() {
        assertEquals(listOf("garage-door"), names(query = "garage"))
        assertEquals(listOf("kitchen-sensor"), names(query = "Kitchen Sensor"))
        assertEquals(listOf("hallway-light", "office-plug"), names(query = "nrf52840dk"))
    }

    @Test
    fun theFilterFieldIgnoresCaseAndSurroundingSpace() {
        assertEquals(listOf("garage-door"), names(query = "  GARAGE  "))
    }

    @Test
    fun anEmptyFilterKeepsEverything() {
        assertEquals(SAMPLE.size, names(query = "   ").size)
    }

    @Test
    fun aFilterThatMatchesNothingLeavesAnEmptyTable() {
        assertEquals(emptyList(), names(query = "cellar"))
    }

    @Test
    fun theTwoFiltersApplyTogether() {
        assertEquals(
            listOf("hallway-light"),
            names(query = "nrf52840dk", mode = DeviceFilterMode.Errors),
        )
    }
}
