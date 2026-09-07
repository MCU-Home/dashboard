// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import org.mcuhome.ui.api.NetworkTransport

/**
 * What a link layer is called on screen.
 *
 * The device table, the board picker and the board registry all print it,
 * and "Wi-Fi" is spelled the one way the brand guide spells it — which is
 * exactly why this is in one place.
 */
fun transportLabel(transport: NetworkTransport): String = when (transport) {
    NetworkTransport.Thread -> "Thread"
    NetworkTransport.WiFi -> "Wi-Fi"
    NetworkTransport.Ethernet -> "Ethernet"
}
