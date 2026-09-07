// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.secret

import org.mcuhome.ui.api.SecretEntry
import org.mcuhome.ui.api.SecretScope
import org.mcuhome.ui.api.SecretScopeIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun entry(
    key: String,
    configs: List<String> = emptyList(),
    devices: List<String> = emptyList(),
) = SecretEntry(key = key, maskedValue = "••••", usedByConfigs = configs, usedByDevices = devices)

class SecretUsedByTest {
    @Test
    fun aKeyNobodyNamesIsCalledUnused() {
        assertEquals("unused", usedByLabel(entry("legacy_api_token")))
        assertTrue(entry("legacy_api_token").unused)
    }

    @Test
    fun oneConfigIsSingular() {
        assertEquals("1 config", usedByLabel(entry("wifi_ssid", configs = listOf("wifi-common.yaml"))))
    }

    @Test
    fun configsAndDevicesAreCountedSeparately() {
        val row = entry("thread_network_key", configs = listOf("a.yaml", "b.yaml"), devices = listOf("garage-door"))
        assertEquals("2 configs · 1 device", usedByLabel(row))
        assertFalse(row.unused)
    }

    @Test
    fun theTableSortsByKeyInBothDirections() {
        val rows = listOf(entry("b_key"), entry("a_key"), entry("c_key"))
        assertEquals(listOf("a_key", "b_key", "c_key"), sortedSecrets(rows, ascending = true).map { it.key })
        assertEquals(listOf("c_key", "b_key", "a_key"), sortedSecrets(rows, ascending = false).map { it.key })
    }
}

class SecretScopeTest {
    private val index = SecretScopeIndex(devices = listOf("garage-door"), buildServers = listOf("workstation"))

    @Test
    fun theTwoScopesThatNameNothingOfferNoNames() {
        assertEquals(emptyList(), scopeNames(SecretScopeKind.Project, index))
        assertEquals(emptyList(), scopeNames(SecretScopeKind.FirmwareKey, index))
    }

    @Test
    fun theTwoScopesThatNameSomethingOfferTheProjectsNames() {
        assertEquals(listOf("garage-door"), scopeNames(SecretScopeKind.Devices, index))
        assertEquals(listOf("workstation"), scopeNames(SecretScopeKind.BuildServer, index))
    }

    @Test
    fun aKindPlusItsNameIsTheScopeTheServerIsAsked() {
        assertEquals(SecretScope.Project, secretScope(SecretScopeKind.Project, null))
        assertEquals(SecretScope.FirmwareKey, secretScope(SecretScopeKind.FirmwareKey, null))
        assertEquals(SecretScope.Device("garage-door"), secretScope(SecretScopeKind.Devices, "garage-door"))
        assertEquals(SecretScope.BuildServer("workstation"), secretScope(SecretScopeKind.BuildServer, "workstation"))
    }

    @Test
    fun aKindWithNothingToNameHasNoScope() {
        assertNull(secretScope(SecretScopeKind.Devices, null))
        assertNull(secretScope(SecretScopeKind.BuildServer, null))
    }
}

class RevealedSecretsTest {
    @Test
    fun nothingIsRevealedUntilAValueArrives() {
        val revealed = RevealedSecrets()
        assertNull(revealed.revealed("wifi_password"))
        assertFalse(revealed.isRevealed("wifi_password"))
    }

    @Test
    fun aRevealedValueIsKeptUnderItsOwnKey() {
        val revealed = RevealedSecrets().with("wifi_password", "example")
        assertEquals("example", revealed.revealed("wifi_password"))
        assertTrue(revealed.isRevealed("wifi_password"))
        assertNull(revealed.revealed("wifi_ssid"))
    }

    @Test
    fun hidingDropsTheValueAgain() {
        val revealed = RevealedSecrets().with("wifi_password", "example").hiding("wifi_password")
        assertNull(revealed.revealed("wifi_password"))
    }

    @Test
    fun theEyeShowsWhatIsHiddenAndHidesWhatIsShown() {
        val shown = RevealedSecrets().toggled("wifi_password", "example")
        assertEquals("example", shown.revealed("wifi_password"))
        assertNull(shown.toggled("wifi_password", "example").revealed("wifi_password"))
    }

    @Test
    fun changingTheScopeForgetsEveryValue() {
        val revealed = RevealedSecrets().with("a", "1").with("b", "2").cleared()
        assertFalse(revealed.isRevealed("a"))
        assertFalse(revealed.isRevealed("b"))
    }
}
