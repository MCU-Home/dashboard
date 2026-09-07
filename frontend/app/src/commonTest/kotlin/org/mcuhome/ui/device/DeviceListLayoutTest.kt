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
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.shell.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals

private fun device(
    name: String = "kitchen-sensor",
    config: ConfigStatus = ConfigStatus(ConfigState.Valid),
    build: BuildStatus = BuildStatus(BuildState.NeverBuilt),
    signed: SignedState = SignedState.Unknown,
    board: String = "nrf7002dk/nrf5340/cpuapp",
    network: NetworkInfo = NetworkInfo(NetworkTransport.Thread, ThreadRole.Mtd),
) = DeviceSummary(name, "Kitchen Sensor", board, config, build, signed, network)

private val built = BuildStatus(BuildState.Built, BuildMethod.Local, 1_788_785_526_000L)

/**
 * What the device list shows at each window size: which columns a table
 * keeps, and which single state a phone's row says out of everything a
 * device could report.
 */
class DeviceListLayoutTest {
    @Test
    fun a_desktop_window_keeps_every_column() {
        assertEquals(DeviceColumn.entries, deviceColumns(WindowSizeClass.Expanded))
    }

    @Test
    fun a_narrower_window_drops_signed_and_network() {
        val columns = deviceColumns(WindowSizeClass.Medium)
        assertEquals(
            listOf(DeviceColumn.Name, DeviceColumn.Board, DeviceColumn.Config, DeviceColumn.Build),
            columns,
        )
        assertEquals(columns, deviceColumns(WindowSizeClass.Compact))
    }

    @Test
    fun errors_come_before_everything_else() {
        val device = device(
            config = ConfigStatus(ConfigState.Errors, errorCount = 2),
            build = BuildStatus(BuildState.Building, progress = Progress(1, 2)),
            signed = SignedState.Signed,
        )
        assertEquals(DeviceRowState.Errors, deviceRowState(device))
        assertEquals("2 errors", deviceRowLabel(device))
        assertEquals(PillTone.Error, deviceRowTone(deviceRowState(device)))
    }

    @Test
    fun a_running_build_comes_before_a_warning() {
        val device = device(
            config = ConfigStatus(ConfigState.Warnings, warningCount = 1),
            build = BuildStatus(BuildState.Building, progress = Progress(142, 380)),
        )
        assertEquals(DeviceRowState.Building, deviceRowState(device))
        assertEquals("building", deviceRowLabel(device))
        assertEquals(PillTone.Accent, deviceRowTone(deviceRowState(device)))
    }

    @Test
    fun a_failed_build_comes_before_a_warning() {
        val device = device(
            config = ConfigStatus(ConfigState.Warnings, warningCount = 1),
            build = BuildStatus(BuildState.Failed),
        )
        assertEquals(DeviceRowState.Failed, deviceRowState(device))
        assertEquals("build failed", deviceRowLabel(device))
    }

    @Test
    fun a_warning_comes_before_the_state_of_the_image() {
        val device = device(
            config = ConfigStatus(ConfigState.Warnings, warningCount = 1),
            build = built,
            signed = SignedState.Signed,
        )
        assertEquals(DeviceRowState.Warnings, deviceRowState(device))
        assertEquals("1 warning", deviceRowLabel(device))
    }

    @Test
    fun a_device_that_was_never_built_says_so() {
        val device = device(build = BuildStatus(BuildState.NeverBuilt))
        assertEquals(DeviceRowState.NotBuilt, deviceRowState(device))
        assertEquals("not built", deviceRowLabel(device))
        assertEquals(PillTone.Neutral, deviceRowTone(deviceRowState(device)))
    }

    @Test
    fun an_unsigned_image_comes_before_a_signed_one() {
        assertEquals(
            DeviceRowState.Unsigned,
            deviceRowState(device(build = built, signed = SignedState.Unsigned)),
        )
        assertEquals(
            DeviceRowState.Signed,
            deviceRowState(device(build = built, signed = SignedState.Signed)),
        )
        assertEquals("signed", deviceRowLabel(device(build = built, signed = SignedState.Signed)))
    }

    @Test
    fun a_device_nothing_has_checked_says_that_instead() {
        val device = device(config = ConfigStatus(ConfigState.Unknown), build = built)
        assertEquals(DeviceRowState.Unchecked, deviceRowState(device))
        assertEquals("not checked", deviceRowLabel(device))
    }

    @Test
    fun a_row_names_the_board_without_its_core() {
        assertEquals("nrf7002dk", shortBoard("nrf7002dk/nrf5340/cpuapp"))
        assertEquals("nrf52840dk", shortBoard("nrf52840dk/nrf52840"))
        assertEquals("esp32c6_devkitc", shortBoard("esp32c6_devkitc/esp32c6/hpcore"))
        assertEquals("qemu", shortBoard("qemu"))
    }

    @Test
    fun a_row_names_the_network_the_way_the_table_does() {
        assertEquals("Thread · MTD", networkLabel(device()))
        assertEquals("Wi-Fi", networkLabel(device(network = NetworkInfo(NetworkTransport.WiFi))))
    }
}
