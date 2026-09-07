// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.config

import org.mcuhome.ui.api.ConfigUserResult
import org.mcuhome.ui.api.ConfigUsersReport
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.SharedConfigSummary
import org.mcuhome.ui.api.ValidationReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val CHANGED = 1_788_785_526_000L

private fun summary(fileName: String, vararg users: String) = SharedConfigSummary(
    fileName = fileName,
    path = "configs/$fileName",
    usedByDevices = users.toList(),
    changedAtEpochMillis = CHANGED,
)

private fun report(vararg users: Pair<String, Int>) = ConfigUsersReport(
    fileName = "wifi-common.yaml",
    users = users.map { (device, errors) ->
        ConfigUserResult(
            device = device,
            report = ValidationReport(
                ok = errors == 0,
                file = "devices/$device/main.yaml",
                checkedAtEpochMillis = CHANGED,
                diagnostics = List(errors) {
                    Diagnostic(severity = DiagnosticSeverity.Error, message = "no", file = "devices/$device/main.yaml")
                },
            ),
        )
    },
)

class ConfigListTest {
    @Test
    fun unusedFileSaysSoRatherThanCountingToZero() {
        assertEquals("unused", usedByLabel(0))
        assertEquals("1 device", usedByLabel(1))
        assertEquals("3 devices", usedByLabel(3))
    }

    @Test
    fun theAddressBarDecidesWhichFileIsOpen() {
        val files = listOf(summary("a.yaml"), summary("b.yaml"))
        assertEquals("b.yaml", openedConfigFile(files, "b.yaml"))
    }

    @Test
    fun anAddressWithoutAFileOpensTheFirstOne() {
        val files = listOf(summary("a.yaml"), summary("b.yaml"))
        assertEquals("a.yaml", openedConfigFile(files, null))
    }

    @Test
    fun anAddressNamingAFileThatIsGoneFallsBackToTheFirst() {
        val files = listOf(summary("a.yaml"), summary("b.yaml"))
        assertEquals("a.yaml", openedConfigFile(files, "deleted.yaml"))
    }

    @Test
    fun anEmptyProjectHasNothingToOpen() {
        assertNull(openedConfigFile(emptyList(), "a.yaml"))
    }

    @Test
    fun aCheckThatFoundNothingSaysEveryDeviceValidates() {
        val result = report("garage-door" to 0, "porch-light" to 0)
        assertEquals("Every device that uses this file validates", usersReportTitle(result))
        assertEquals("garage-door, porch-light", usersReportMessage(result))
    }

    @Test
    fun aCheckWithFindingsCountsTheDevicesThatFailed() {
        val result = report("garage-door" to 2, "porch-light" to 0)
        assertEquals("1 of 2 devices report an error", usersReportTitle(result))
    }

    @Test
    fun aFragmentNobodyIncludesIsCheckedAgainstNothing() {
        val result = report()
        assertEquals("No device includes this file", usersReportTitle(result))
        assertEquals(
            "Nothing was checked. A device includes a fragment with !include ../../configs/wifi-common.yaml.",
            usersReportMessage(result),
        )
    }
}
