// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import org.mcuhome.ui.api.BoardInfo
import org.mcuhome.ui.api.NetworkTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val BOARDS = listOf(
    BoardInfo(
        target = "nrf52840dk/nrf52840",
        displayName = "Nordic nRF52840 DK",
        vendor = "Nordic Semiconductor",
        transports = listOf(NetworkTransport.Thread),
    ),
    BoardInfo(
        target = "esp32c6_devkitc/esp32c6/hpcore",
        displayName = "Espressif ESP32-C6-DevKitC",
        vendor = "Espressif",
        transports = listOf(NetworkTransport.Thread, NetworkTransport.WiFi),
    ),
    BoardInfo(
        target = "nrf5340dk/nrf5340/cpuapp",
        displayName = "Nordic nRF5340 DK",
        vendor = "Nordic Semiconductor",
        transports = listOf(NetworkTransport.Thread),
        planned = true,
        plannedReason = "not brought up yet",
    ),
)

/** The board search of the New device dialog. */
class BoardPickerTest {
    @Test
    fun anEmptySearchOffersEveryBoardWithThePlannedOnesLast() {
        assertEquals(
            listOf("nrf52840dk/nrf52840", "esp32c6_devkitc/esp32c6/hpcore", "nrf5340dk/nrf5340/cpuapp"),
            filterBoards(BOARDS, "").map { it.target },
        )
    }

    @Test
    fun aBoardIsFoundByItsTargetItsNameOrItsVendor() {
        assertEquals(2, filterBoards(BOARDS, "nrf5").size)
        assertEquals(listOf("esp32c6_devkitc/esp32c6/hpcore"), filterBoards(BOARDS, "ESP32").map { it.target })
        assertEquals(2, filterBoards(BOARDS, "Nordic").size)
    }

    @Test
    fun theSearchIgnoresCaseAndSurroundingSpace() {
        assertEquals(1, filterBoards(BOARDS, "  espressif ").size)
    }

    @Test
    fun aPlannedBoardIsStillOfferedSoThePickerCanSayWhyItIsMissing() {
        val planned = filterBoards(BOARDS, "nrf5340").single()
        assertTrue(planned.planned)
    }

    @Test
    fun aSearchThatMatchesNothingOffersNothing() {
        assertEquals(emptyList(), filterBoards(BOARDS, "stm32").map { it.target })
    }

    @Test
    fun aBoardIsDescribedByItsNameAndTheNetworksItJoins() {
        assertEquals("Nordic nRF52840 DK · Thread", boardDescription(BOARDS[0]))
        assertEquals("Espressif ESP32-C6-DevKitC · Thread, Wi-Fi", boardDescription(BOARDS[1]))
    }
}
