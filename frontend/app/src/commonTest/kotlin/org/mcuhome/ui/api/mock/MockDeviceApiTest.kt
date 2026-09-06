// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.test.runTest
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.NewDeviceRequest
import org.mcuhome.ui.api.SaveResult
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.api.Starter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The device area of the mock: the table the design shows, reading and
 * writing a file, and the conflict a second writer causes.
 */
class MockDeviceApiTest {
    @Test
    fun listAnswersTheSixSampleDevicesInTheDesignsOrder() = runTest {
        val api = mockApi()
        assertEquals(
            listOf("kitchen-sensor", "hallway-light", "garage-door", "bench-node", "office-plug", "balcony-climate"),
            api.device.list().map { it.name },
        )
    }

    @Test
    fun theTableStatesMatchTheDesign() = runTest {
        val api = mockApi()
        val devices = api.device.list().associateBy { it.name }

        assertEquals(ConfigState.Valid, devices.getValue("kitchen-sensor").config.state)
        assertEquals(SignedState.Signed, devices.getValue("kitchen-sensor").signed)

        assertEquals(ConfigState.Errors, devices.getValue("hallway-light").config.state)
        assertEquals(2, devices.getValue("hallway-light").config.errorCount)
        assertEquals(BuildState.NeverBuilt, devices.getValue("hallway-light").build.state)

        assertEquals(ConfigState.Warnings, devices.getValue("office-plug").config.state)
        assertEquals(1, devices.getValue("office-plug").config.warningCount)

        assertEquals(SignedState.Unsigned, devices.getValue("bench-node").signed)
        assertEquals(BuildState.NeverBuilt, devices.getValue("balcony-climate").build.state)
    }

    @Test
    fun getAnswersTheFileAndWhatTheRailShows() = runTest {
        val api = mockApi()
        val detail = api.device.get("garage-door")

        assertEquals("devices/garage-door/main.yaml", detail.path)
        assertEquals(GARAGE_DOOR_YAML, detail.yaml)
        assertEquals(listOf("wifi-common.yaml", "matter-defaults.yaml"), detail.includes)
        assertEquals(3, detail.resolvedSecretCount)
        assertTrue(detail.artifacts.isNotEmpty())
        assertEquals(true, detail.pairing?.present)
    }

    @Test
    fun getRefusesAnUnknownDevice() = runTest {
        val api = mockApi()
        val error = assertFailsWith<ApiException> { api.device.get("no-such-device") }
        assertTrue("no-such-device" in error.error.message)
    }

    @Test
    fun savingWithTheRevisionThatWasReadSucceedsAndMovesTheRevisionOn() = runTest {
        val api = mockApi()
        val before = api.device.get("bench-node")

        val result = api.device.save("bench-node", before.yaml + "\n", before.revision)

        val saved = assertIs<SaveResult.Saved>(result)
        assertNotEquals(before.revision, saved.revision)
        assertEquals(saved.revision, api.device.get("bench-node").revision)
    }

    @Test
    fun savingAgainstAStaleRevisionReportsTheConflictWithTheCurrentText() = runTest {
        val api = mockApi()
        val before = api.device.get("bench-node")
        api.device.save("bench-node", "# a first writer\n" + before.yaml, before.revision)

        val result = api.device.save("bench-node", "# a second writer\n" + before.yaml, before.revision)

        val conflict = assertIs<SaveResult.Conflict>(result)
        assertTrue(conflict.currentText.startsWith("# a first writer"))
        assertNotEquals(before.revision, conflict.currentRevision)
    }

    @Test
    fun aNewDeviceGetsAStarterFileAndAppearsInTheList() = runTest {
        val api = mockApi()
        val created = api.device.new(
            NewDeviceRequest(
                name = "porch-light",
                board = "nrf52840dk/nrf52840",
                friendlyName = "Porch Light",
                starter = Starter.Light,
            ),
        )

        assertEquals("Porch Light", created.friendlyName)
        assertEquals(ConfigState.Valid, created.config.state)
        assertTrue(api.device.list().any { it.name == "porch-light" })
        assertTrue("device_type: on_off_light" in api.device.get("porch-light").yaml)
    }

    @Test
    fun aNewDeviceOnAPlannedBoardIsRefusedWithTheReason() = runTest {
        val api = mockApi()
        val error = assertFailsWith<ApiException> {
            api.device.new(NewDeviceRequest(name = "later-node", board = "nrf5340dk/nrf5340/cpuapp"))
        }
        assertTrue("not brought up yet" in error.error.message)
    }

    @Test
    fun cleanTakesTheBuildStateBackToNeverBuilt() = runTest {
        val api = mockApi()
        api.device.clean("kitchen-sensor")

        val device = api.device.list().first { it.name == "kitchen-sensor" }
        assertEquals(BuildState.NeverBuilt, device.build.state)
        assertEquals(SignedState.Unknown, device.signed)
        assertTrue(api.device.get("kitchen-sensor").artifacts.isEmpty())
    }

    @Test
    fun theBoardListCarriesThePlannedBoardWithItsReason() = runTest {
        val api = mockApi()
        val planned = api.device.boards().boards.filter { it.planned }
        assertEquals(listOf("nrf5340dk/nrf5340/cpuapp"), planned.map { it.target })
        assertEquals("not brought up yet", planned.single().plannedReason)
    }
}
