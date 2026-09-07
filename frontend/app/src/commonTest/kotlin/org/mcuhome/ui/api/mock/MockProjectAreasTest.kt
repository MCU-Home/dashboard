// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.test.runTest
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.Availability
import org.mcuhome.ui.api.FlashMode
import org.mcuhome.ui.api.FlashRequest
import org.mcuhome.ui.api.OptionOrigin
import org.mcuhome.ui.api.SaveResult
import org.mcuhome.ui.api.SecretScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The areas around the devices: secrets, shared configurations, project
 * options, pairing — and the three capabilities that answer "not
 * available" until the workbench provides them.
 */
class MockProjectAreasTest {
    @Test
    fun aSecretListCarriesNoValuesAndRevealHandsOutExactlyOne() = runTest {
        val api = mockApi()
        val list = api.secret.list(SecretScope.Project)

        assertEquals("secrets/main.yaml", list.path)
        assertTrue(list.entries.all { it.maskedValue.all { char -> char == '•' } })

        val value = api.secret.reveal(SecretScope.Project, "wifi_ssid")
        assertEquals("mcuhome-demo", value)
    }

    @Test
    fun theScopeIndexNamesOnlyWhatHasASecretsFile() = runTest {
        val api = mockApi()
        val index = api.secret.scopes()
        val devices = api.device.list().map { it.name }

        assertTrue(index.devices.isNotEmpty())
        assertTrue(index.devices.all { it in devices })
        // balcony-climate is the sample device without commissioning
        // credentials, so it has no secrets file of its own.
        assertFalse("balcony-climate" in index.devices)
        index.devices.forEach { device -> api.secret.list(SecretScope.Device(device)) }
        assertEquals(listOf("workstation"), index.buildServers)
    }

    @Test
    fun usedByFollowsTheFilesThatReferTheSecret() = runTest {
        val api = mockApi()
        val entries = api.secret.list(SecretScope.Project).entries.associateBy { it.key }

        assertEquals(listOf("wifi-common.yaml"), entries.getValue("wifi_ssid").usedByConfigs)
        assertEquals(emptyList(), entries.getValue("wifi_ssid").usedByDevices)
        assertEquals(2, entries.getValue("thread_network_key").usedByConfigs.size)
        assertEquals(6, entries.getValue("ota_server_url").usedByDevices.size)
        assertTrue(entries.getValue("legacy_api_token").unused)
    }

    @Test
    fun settingAndDeletingASecretChangesWhatTheListShows() = runTest {
        val api = mockApi()
        api.secret.set(SecretScope.Project, "new_key", "abc")
        assertEquals("•••", api.secret.list(SecretScope.Project).entries.first { it.key == "new_key" }.maskedValue)

        api.secret.delete(SecretScope.Project, "new_key")
        assertTrue(api.secret.list(SecretScope.Project).entries.none { it.key == "new_key" })
        assertFailsWith<ApiException> { api.secret.reveal(SecretScope.Project, "new_key") }
    }

    @Test
    fun theSharedConfigurationsCarryTheUserCountsTheDesignShows() = runTest {
        val api = mockApi()
        val configs = api.config.list().associateBy { it.fileName }

        assertEquals(3, configs.getValue("thread-mtd-common.yaml").usedByDevices.size)
        assertEquals(2, configs.getValue("thread-ftd-common.yaml").usedByDevices.size)
        assertEquals(1, configs.getValue("wifi-common.yaml").usedByDevices.size)
        assertEquals(6, configs.getValue("matter-defaults.yaml").usedByDevices.size)
        assertTrue(configs.getValue("sensor-reporting.yaml").usedByDevices.isEmpty())
    }

    @Test
    fun readingASharedConfigurationNamesItsSecretsAndWritingDetectsAConflict() = runTest {
        val api = mockApi()
        val file = api.config.read("wifi-common.yaml")

        assertEquals(listOf("wifi_ssid", "wifi_password"), file.referencedSecrets.map { it.key })
        assertTrue(file.referencedSecrets.all { it.set })

        api.config.write("wifi-common.yaml", file.text + "\n", file.revision)
        val second = api.config.write("wifi-common.yaml", file.text + "\n\n", file.revision)
        assertIs<SaveResult.Conflict>(second)
    }

    @Test
    fun validatingTheUsersOfASharedConfigurationReportsOnePerDevice() = runTest {
        val api = mockApi()
        val report = api.config.validateUsers("thread-ftd-common.yaml")

        assertEquals(listOf("hallway-light", "office-plug"), report.users.map { it.device })
        assertTrue(!report.ok)
    }

    @Test
    fun everyOptionStatesTheLayerItsValueCameFrom() = runTest {
        val api = mockApi()
        val options = api.project.options().associateBy { it.name }

        assertEquals(OptionOrigin.Project, options.getValue("default_builder").origin)
        assertEquals(OptionOrigin.User, options.getValue("jobs").origin)
        assertEquals(OptionOrigin.System, options.getValue("build.env_store").origin)
        assertEquals(OptionOrigin.Default, options.getValue("signing_key").origin)
    }

    @Test
    fun settingAnOptionMovesItToTheProjectLayerAndUnsettingDropsItBack() = runTest {
        val api = mockApi()

        val set = api.project.setOption("build.python", "python3.13")
        assertEquals(OptionOrigin.Project, set.origin)
        assertEquals("python3.13", set.value)

        val unset = api.project.unsetOption("build.python")
        assertEquals(OptionOrigin.Default, unset.origin)
        assertEquals(null, unset.value)
    }

    @Test
    fun anOptionOnlyAcceptsAValueItDeclares() = runTest {
        val api = mockApi()
        val error = assertFailsWith<ApiException> { api.project.setOption("build.mode", "nonsense") }
        assertTrue("container, subprocess" in error.error.hint.orEmpty())
    }

    @Test
    fun unsettingAnOptionAnotherLayerSetIsRefused() = runTest {
        val api = mockApi()
        val error = assertFailsWith<ApiException> { api.project.unsetOption("jobs") }
        assertTrue("user" in error.error.hint.orEmpty())
    }

    @Test
    fun drawingPairingCredentialsWritesThemIntoTheDeviceAndItsSecrets() = runTest {
        val api = mockApi()
        assertFailsWith<ApiException> { api.pairing.get("balcony-climate") }

        val drawn = api.pairing.draw("balcony-climate")

        assertTrue(!drawn.replaced)
        assertEquals(drawn.credentials, api.pairing.get("balcony-climate"))
        assertEquals(
            drawn.credentials.discriminator.toString(),
            api.secret.reveal(SecretScope.Device("balcony-climate"), "matter_discriminator"),
        )
        assertTrue("!secret matter_discriminator" in api.device.get("balcony-climate").yaml)
        assertTrue(api.device.validate("balcony-climate").ok)
    }

    @Test
    fun drawingCredentialsThatChangeTheFileGivesItANewRevision() = runTest {
        val api = mockApi()
        val before = api.device.get("balcony-climate")

        api.pairing.draw("balcony-climate")
        val after = api.device.get("balcony-climate")

        assertTrue(after.revision != before.revision)
        val conflict = assertIs<SaveResult.Conflict>(
            api.device.save("balcony-climate", "device:\n  name: balcony-climate\n", before.revision),
        )
        assertEquals(after.revision, conflict.currentRevision)
        assertEquals(after.yaml, conflict.currentText)
    }

    @Test
    fun drawingCredentialsThatAreAlreadyReferencedLeavesTheFileAlone() = runTest {
        val api = mockApi()
        val before = api.device.get("garage-door")

        api.pairing.draw("garage-door", force = true)
        val after = api.device.get("garage-door")

        assertEquals(before.revision, after.revision)
        assertEquals(before.yaml, after.yaml)
    }

    @Test
    fun drawingOverExistingCredentialsNeedsAConfirmation() = runTest {
        val api = mockApi()
        assertFailsWith<ApiException> { api.pairing.draw("garage-door") }

        val drawn = api.pairing.draw("garage-door", force = true)
        assertTrue(drawn.replaced)
    }

    @Test
    fun flashFirstTimeSetupAndTheDeviceLogAnswerNotAvailableWithAReason() = runTest {
        val api = mockApi()

        val flashOptions = assertIs<Availability.NotAvailable>(api.flash.options("garage-door"))
        assertTrue("not built yet" in flashOptions.reason)

        val flash = api.flash.start(FlashRequest("garage-door", "firmware.signed.bin", FlashMode.Recovery))
        assertIs<Availability.NotAvailable>(flash)

        assertIs<Availability.NotAvailable>(api.setup.start("garage-door"))
        assertIs<Availability.NotAvailable>(api.log.open("garage-door"))
    }

    @Test
    fun theServerReportsTheThreeCapabilitiesItDoesNotHave() = runTest {
        val api = mockApi()
        val info = api.server.info()

        assertEquals("my-home", info.projectName)
        assertEquals(
            listOf("flash", "first-time-setup", "device-log"),
            info.capabilities.filterNot { it.available }.map { it.name },
        )
        assertTrue(info.capabilities.filterNot { it.available }.all { !it.reason.isNullOrBlank() })
    }

    @Test
    fun thePublicKeyIsTheOnlyKeyMaterialTheApiHandsOut() = runTest {
        val api = mockApi()
        val key = api.project.publicKey()

        assertTrue(key.pem.startsWith("-----BEGIN PUBLIC KEY-----"))
        assertEquals("secrets/firmware/signing.pem", key.keyFile)
    }
}
