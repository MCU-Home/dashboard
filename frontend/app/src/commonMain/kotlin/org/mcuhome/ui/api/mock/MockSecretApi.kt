// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.flow.update
import org.mcuhome.ui.api.SecretApi
import org.mcuhome.ui.api.SecretEntry
import org.mcuhome.ui.api.SecretList
import org.mcuhome.ui.api.SecretScope
import org.mcuhome.ui.api.SecretScopeIndex

/**
 * The project's secrets.
 *
 * The mock enforces the rule the real back end has to enforce: a list
 * answers with masked values and nothing else, and the only call that
 * hands out a value takes one key and returns one string.
 */
internal class MockSecretApi(private val context: MockContext) : SecretApi {
    override suspend fun scopes(): SecretScopeIndex {
        val secrets = context.state.value.secrets
        return SecretScopeIndex(
            // Only the devices that have a file of their own: a scope the
            // interface offers has to have something behind it, and a
            // device without commissioning credentials has no secrets yet.
            devices = context.state.value.devices.map { it.name }.filter { SecretScope.Device(it) in secrets },
            buildServers = secrets.keys.filterIsInstance<SecretScope.BuildServer>().map { it.server },
        )
    }

    override suspend fun list(scope: SecretScope): SecretList {
        val state = context.state.value
        val values = state.secrets[scope] ?: throw notFound("There is no secrets file for that scope yet.")
        return SecretList(
            scope = scope,
            path = secretPath(scope),
            entries = values.map { (key, value) -> entry(state, scope, key, value) },
        )
    }

    override suspend fun reveal(scope: SecretScope, key: String): String = context.state.value.secrets[scope]?.get(key)
        ?: throw notFound("The secret \"$key\" is not set in that scope.")

    override suspend fun set(
        scope: SecretScope,
        key: String,
        value: String,
    ) {
        if (key.isBlank()) throw invalid("A secret needs a key.")
        context.state.update { state ->
            val values = LinkedHashMap(state.secrets[scope].orEmpty())
            values[key] = value
            state.copy(secrets = state.secrets + (scope to values))
        }
    }

    override suspend fun delete(scope: SecretScope, key: String) {
        val values = context.state.value.secrets[scope]
        if (values == null || key !in values) throw notFound("The secret \"$key\" is not set in that scope.")
        context.state.update { state ->
            state.copy(secrets = state.secrets + (scope to values.filterKeys { it != key }))
        }
    }
}

/** Where a scope's secrets live, as the screen prints it. */
internal fun secretPath(scope: SecretScope): String = when (scope) {
    SecretScope.Project -> "secrets/main.yaml"
    is SecretScope.Device -> "secrets/devices/${scope.device}.yaml"
    is SecretScope.BuildServer -> "secrets/build-server/${scope.server}.yaml"
    SecretScope.FirmwareKey -> "secrets/firmware/"
}

/**
 * One row, with its used-by column derived from the files rather than
 * declared: a secret is used exactly where a `!secret` reference names it,
 * so removing the reference in the editor empties the column.
 */
private fun entry(
    state: MockState,
    scope: SecretScope,
    key: String,
    value: String,
): SecretEntry {
    val devices = when (scope) {
        is SecretScope.Device -> listOfNotNull(state.device(scope.device))
        SecretScope.Project -> state.devices
        else -> emptyList()
    }.filter { key in referencedSecrets(it.text) }.map { it.name }
    val configs = if (scope == SecretScope.Project) {
        state.configs.filter { key in referencedSecrets(it.text) }.map { it.fileName }
    } else {
        emptyList()
    }
    return SecretEntry(key = key, maskedValue = mask(value), usedByDevices = devices, usedByConfigs = configs)
}
