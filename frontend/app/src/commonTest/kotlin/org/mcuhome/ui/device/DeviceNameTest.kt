// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import org.mcuhome.ui.api.Starter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BOARD = "nrf52840dk/nrf52840"

/**
 * What the New device dialog accepts, and what it hands to the API when
 * it does.
 */
class DeviceNameTest {
    @Test
    fun aNameOfLowercaseLettersDigitsAndHyphensIsAccepted() {
        assertTrue(isValidDeviceName("porch-light"))
        assertTrue(isValidDeviceName("sensor2"))
        assertTrue(isValidDeviceName("a"))
    }

    @Test
    fun aNameThatCouldNotBeAFolderOrAHostNameIsRefused() {
        assertFalse(isValidDeviceName(""))
        assertFalse(isValidDeviceName("Porch-Light"))
        assertFalse(isValidDeviceName("2nd-light"))
        assertFalse(isValidDeviceName("-light"))
        assertFalse(isValidDeviceName("porch_light"))
        assertFalse(isValidDeviceName("porch light"))
        assertFalse(isValidDeviceName("porch.light"))
    }

    @Test
    fun theFriendlyNameIsTheDeviceNameInWords() {
        assertEquals("Porch Light", friendlyNameFor("porch-light"))
        assertEquals("Sensor2", friendlyNameFor("sensor2"))
        assertEquals("", friendlyNameFor(""))
    }

    @Test
    fun aCompleteFormBecomesARequest() {
        val request = newDeviceRequest(
            name = "porch-light",
            board = BOARD,
            friendlyName = "Porch Light",
            starter = StarterChoice.Light,
            copyOf = null,
        )
        assertEquals("porch-light", request?.name)
        assertEquals(BOARD, request?.board)
        assertEquals("Porch Light", request?.friendlyName)
        assertEquals(Starter.Light, request?.starter)
    }

    @Test
    fun aFormWithoutAFriendlyNameLeavesTheServerToDeriveOne() {
        val request = newDeviceRequest("porch-light", BOARD, "   ", StarterChoice.Minimal, copyOf = null)
        assertNull(request?.friendlyName)
        assertEquals(Starter.Minimal, request?.starter)
    }

    @Test
    fun anIncompleteFormIsNoRequestAtAll() {
        assertNull(newDeviceRequest("Porch-Light", BOARD, "", StarterChoice.Minimal, copyOf = null))
        assertNull(newDeviceRequest("porch-light", board = null, friendlyName = "", StarterChoice.Minimal, null))
        assertNull(newDeviceRequest("porch-light", BOARD, "", StarterChoice.CopyOf, copyOf = null))
    }

    @Test
    fun copyingAnExistingDeviceCarriesTheDeviceItCopies() {
        val request = newDeviceRequest("porch-light", BOARD, "", StarterChoice.CopyOf, copyOf = "office-plug")
        assertEquals(Starter.CopyOf("office-plug"), request?.starter)
    }
}
