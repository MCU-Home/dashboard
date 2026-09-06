// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.test.runTest
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validation follows the text, which is the whole point of computing it
 * rather than storing it: every test here changes a file and checks that
 * the diagnostics change with it.
 */
class MockValidationTest {
    @Test
    fun theSampleFilesProduceTheDiagnosticsTheDesignShows() = runTest {
        val api = mockApi()

        assertTrue(api.device.validate("kitchen-sensor").ok)
        assertEquals(0, api.device.validate("kitchen-sensor").diagnostics.size)

        val hallway = api.device.validate("hallway-light")
        assertFalse(hallway.ok)
        assertEquals(2, hallway.errorCount)

        val office = api.device.validate("office-plug")
        assertTrue(office.ok)
        assertEquals(1, office.warningCount)
    }

    @Test
    fun aWarningCarriesTheFileLineColumnKeyAndHint() = runTest {
        val api = mockApi()
        val warning = api.device.validate("office-plug").diagnostics.single()

        assertEquals(DiagnosticSeverity.Warning, warning.severity)
        assertEquals("devices/office-plug/main.yaml", warning.file)
        assertEquals("hardware.peripherals.relay", warning.key)
        assertNotNull(warning.line)
        assertNotNull(warning.column)
        assertNotNull(warning.hint)
        assertTrue("no pin given" in warning.message)
    }

    @Test
    fun givingThePinAPeripheralIsMissingClearsTheWarning() = runTest {
        val api = mockApi()
        val before = api.device.get("office-plug")
        assertEquals(1, before.diagnostics.size)

        val fixed = before.yaml.replace(
            "    relay:\n      driver: gpio_output\n",
            "    relay:\n      driver: gpio_output\n      pin: 7\n",
        )
        val report = api.device.validate("office-plug", fixed)

        assertTrue(report.diagnostics.isEmpty())
    }

    @Test
    fun savingAFixMovesTheDeviceTablesPill() = runTest {
        val api = mockApi()
        val before = api.device.get("hallway-light")
        val fixed = before.yaml
            .replace("automations:\n  - trigger: on_boot\n    action: restore_state\n\n", "")
            .replace("    default_level: !secret hallway_dim_default\n", "")

        api.device.save("hallway-light", fixed, before.revision)

        val row = api.device.list().first { it.name == "hallway-light" }
        assertEquals(ConfigState.Valid, row.config.state)
    }

    @Test
    fun anUnknownSectionIsReportedAtItsOwnLine() = runTest {
        val api = mockApi()
        val text = "device:\n  board: nrf52840dk/nrf52840\n\nnonsense:\n  key: value\n"

        val diagnostic = api.device.validate("bench-node", text).diagnostics.single()

        assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
        assertEquals(4, diagnostic.line)
        assertEquals("nonsense", diagnostic.key)
    }

    @Test
    fun anUnknownBoardIsReportedWithTheListOfSupportedOnes() = runTest {
        val api = mockApi()
        val text = "device:\n  board: made-up-board\n"

        val diagnostic = api.device.validate("bench-node", text).diagnostics.single()

        assertEquals("device.board", diagnostic.key)
        assertTrue("nrf7002dk/nrf5340/cpuapp" in diagnostic.hint.orEmpty())
    }

    @Test
    fun aSecretNobodySetIsReportedWhereItIsReferenced() = runTest {
        val api = mockApi()
        val text = "device:\n  board: nrf52840dk/nrf52840\n  friendly_name: !secret nowhere_near_set\n"

        val diagnostic = api.device.validate("bench-node", text).diagnostics.single()

        assertEquals("nowhere_near_set", diagnostic.key)
        assertEquals(3, diagnostic.line)
    }

    @Test
    fun aTabInTheIndentationIsAnErrorOfItsOwn() = runTest {
        val api = mockApi()
        val text = "device:\n\tboard: nrf52840dk/nrf52840\n"

        val diagnostics = api.device.validate("bench-node", text).diagnostics

        assertTrue(diagnostics.any { "tab" in it.message })
    }

    @Test
    fun validatingTheFileOnDiskIsRecordedAsAJobAndValidatingTheEditorIsNot() = runTest {
        val api = mockApi()
        val before = api.job.list().size

        api.device.validate("hallway-light", "device:\n  board: nrf52840dk/nrf52840\n")
        assertEquals(before, api.job.list().size)

        api.device.validate("hallway-light")
        val added = api.job.list().last()
        assertEquals("hallway-light", added.device)
        assertTrue("2 error" in added.summary.orEmpty())
    }
}
