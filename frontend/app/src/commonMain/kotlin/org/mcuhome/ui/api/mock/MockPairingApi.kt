// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.flow.update
import org.mcuhome.ui.api.PairingApi
import org.mcuhome.ui.api.PairingCredentials
import org.mcuhome.ui.api.PairingDrawResult
import org.mcuhome.ui.api.SecretScope
import kotlin.math.abs

private const val DISCRIMINATOR_RANGE = 4096

/** A discriminator is always printed as four digits, a passcode as eight. */
private const val DISCRIMINATOR_DIGITS = 4
private const val PASSCODE_DIGITS = 8
private const val PASSCODE_BASE = 10_000_000
private const val PASSCODE_RANGE = 80_000_000

/** The manual pairing code is eleven digits, grouped four-three-four. */
private const val GROUP_ONE_END = 4
private const val GROUP_TWO_END = 7
private const val GROUP_THREE_END = 11

/**
 * A device's Matter commissioning credentials.
 *
 * Drawing them does three things at once, exactly as the command line
 * does: it writes the values into the device's own secrets file, it puts
 * the two `!secret` references into the configuration if they are not
 * there yet, and it hands the codes back so the dialog can show them.
 */
internal class MockPairingApi(private val context: MockContext) : PairingApi {
    override suspend fun get(device: String): PairingCredentials {
        val known = context.requireDevice(device)
        return known.pairing ?: throw notFound(
            "$device has no commissioning credentials yet.",
            hint = "draw them with \"Draw new credentials\" — they are written into the device's secrets file",
        )
    }

    override suspend fun draw(device: String, force: Boolean): PairingDrawResult {
        val known = context.requireDevice(device)
        if (known.pairing != null && !force) {
            throw refused(
                "$device already has commissioning credentials.",
                hint = "drawing new ones invalidates every code already handed out; confirm to replace them",
            )
        }
        val credentials = drawCredentials(device, known.pairing != null)
        val updated = known.copy(pairing = credentials, text = withPairingReferences(known.text))
        context.state.update { state ->
            state.withDevice(updated).copy(
                secrets = state.secrets + (
                    SecretScope.Device(device) to linkedMapOf(
                        "matter_discriminator" to credentials.discriminator.toString(),
                        "matter_passcode" to credentials.passcode.toString(),
                        "matter_salt" to "U1BBS0UyUCBTYW1wbGUgU2FsdA==",
                        "matter_iterations" to "1000",
                    )
                    ),
            )
        }
        context.deviceChanged(updated)
        return PairingDrawResult(credentials, replaced = known.pairing != null)
    }
}

/**
 * Credentials derived from the device name and whether this is a redraw,
 * so the same mock always produces the same codes for the same device.
 */
private fun drawCredentials(device: String, redraw: Boolean): PairingCredentials {
    val seed = abs((device + if (redraw) "-again" else "").hashCode())
    val discriminator = seed % DISCRIMINATOR_RANGE
    val passcode = PASSCODE_BASE + seed % PASSCODE_RANGE
    val digits = passcode.toString().padStart(PASSCODE_DIGITS, '0') +
        discriminator.toString().padStart(DISCRIMINATOR_DIGITS, '0')
    return PairingCredentials(
        device = device,
        discriminator = discriminator,
        passcode = passcode,
        manualCode = digits.substring(0, GROUP_ONE_END) + "-" +
            digits.substring(GROUP_ONE_END, GROUP_TWO_END) + "-" +
            digits.substring(GROUP_TWO_END, GROUP_THREE_END),
        qrPayload = "MT:Y.K9042C00KA${discriminator.toString().padStart(DISCRIMINATOR_DIGITS, '0')}G00",
        secretsFile = "secrets/devices/$device.yaml",
        testCredentials = false,
    )
}

/**
 * The two `!secret` lines under `network.matter`, added where they are
 * missing. A device that already refers to them is left alone — the values
 * behind the references are what changed, not the configuration.
 */
private fun withPairingReferences(text: String): String {
    if ("matter_discriminator" in text) return text
    val lines = text.lines().toMutableList()
    val header = lines.indexOfFirst { it.trim() == "matter:" }
    if (header < 0) return text
    val indent = " ".repeat(lines[header].takeWhile { it == ' ' }.length + 2)
    lines.add(header + 1, "${indent}passcode: !secret matter_passcode")
    lines.add(header + 1, "${indent}discriminator: !secret matter_discriminator")
    return lines.joinToString("\n")
}
