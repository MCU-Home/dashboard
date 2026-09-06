// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which secrets file a key lives in.
 *
 * The four scopes are the four places an MCUHome project keeps secrets, and
 * the three that name something carry that name: a device's own file, a
 * build server's credentials, the firmware signing key material.
 */
@Serializable
sealed interface SecretScope {
    /** `secrets/main.yaml` — the project's shared secrets. */
    @Serializable
    @SerialName("project")
    data object Project : SecretScope

    /** `secrets/devices/<device>.yaml`. */
    @Serializable
    @SerialName("device")
    data class Device(val device: String) : SecretScope

    /** `secrets/build-server/<server>.yaml`. */
    @Serializable
    @SerialName("build-server")
    data class BuildServer(val server: String) : SecretScope

    /** `secrets/firmware/` — the signing key and its public half. */
    @Serializable
    @SerialName("firmware-key")
    data object FirmwareKey : SecretScope
}

/**
 * One row of the secrets table.
 *
 * The value itself is not here. [maskedValue] is dots — as many as the
 * value has characters, which is all the design shows — and the real
 * string arrives only from `secret/reveal`, for one key, when the user
 * asks. A list never carries secret material.
 */
@Serializable
data class SecretEntry(
    val key: String,
    val maskedValue: String,
    val usedByDevices: List<String> = emptyList(),
    val usedByConfigs: List<String> = emptyList(),
) {
    val unused: Boolean get() = usedByDevices.isEmpty() && usedByConfigs.isEmpty()
}

/** One secrets file with its rows. */
@Serializable
data class SecretList(val scope: SecretScope, val path: String, val entries: List<SecretEntry> = emptyList())

/** The scopes that name something, so the interface can offer the choice. */
@Serializable
data class SecretScopeIndex(val devices: List<String> = emptyList(), val buildServers: List<String> = emptyList())
