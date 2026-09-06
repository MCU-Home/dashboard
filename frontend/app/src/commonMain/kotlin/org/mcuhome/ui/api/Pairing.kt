// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.Serializable

/** What the device rail says about pairing without opening the dialog. */
@Serializable
data class PairingSummary(
    val present: Boolean,
    /** Dots, as the rail draws them; the value itself is in the dialog. */
    val maskedDiscriminator: String,
    val testCredentials: Boolean = false,
)

/**
 * One device's Matter commissioning credentials.
 *
 * The passcode travels in the clear, and deliberately so: the manual
 * pairing code and the QR payload beside it are derived from exactly that
 * number, so masking it while printing them would protect nothing. The
 * dialog masks it on screen behind an eye toggle because a screenshot or
 * a shoulder is a real risk; that is a rendering decision, not a transport
 * one — which is the opposite of how secrets are handled, where the value
 * genuinely stays on the server until it is asked for.
 *
 * [secretsFile] is where the values live, so the dialog can say it.
 */
@Serializable
data class PairingCredentials(
    val device: String,
    val discriminator: Int,
    val passcode: Int,
    val manualCode: String,
    val qrPayload: String,
    val secretsFile: String,
    val testCredentials: Boolean = false,
)

/** What drawing new credentials produced. */
@Serializable
data class PairingDrawResult(
    val credentials: PairingCredentials,
    /** True when credentials were already there and [PairingApi.draw] replaced them. */
    val replaced: Boolean,
)
