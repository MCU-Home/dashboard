// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.secret

import org.mcuhome.ui.api.SecretEntry
import org.mcuhome.ui.api.SecretScope
import org.mcuhome.ui.api.SecretScopeIndex

/**
 * The four places a project keeps secrets, as the segmented control
 * offers them.
 *
 * Two of them are a group rather than a file: a project has one secrets
 * file per device and one per build server, so picking `Devices` still
 * leaves the question which device — which is why a scope is a kind plus
 * a name and not a single value.
 */
enum class SecretScopeKind(val label: String) {
    Project("Project"),
    Devices("Devices"),
    BuildServer("Build server"),
    FirmwareKey("Firmware key"),
}

/** The names the kind offers, empty for the two that name nothing. */
fun scopeNames(kind: SecretScopeKind, index: SecretScopeIndex): List<String> = when (kind) {
    SecretScopeKind.Project, SecretScopeKind.FirmwareKey -> emptyList()
    SecretScopeKind.Devices -> index.devices
    SecretScopeKind.BuildServer -> index.buildServers
}

/**
 * The scope the screen asks the server about. Null while a kind that
 * names something has nothing to name — a project with no build server
 * has no build-server secrets file to read.
 */
fun secretScope(kind: SecretScopeKind, name: String?): SecretScope? = when (kind) {
    SecretScopeKind.Project -> SecretScope.Project
    SecretScopeKind.FirmwareKey -> SecretScope.FirmwareKey
    SecretScopeKind.Devices -> name?.let { SecretScope.Device(it) }
    SecretScopeKind.BuildServer -> name?.let { SecretScope.BuildServer(it) }
}

/**
 * The used-by column: where the key is actually named.
 *
 * It is derived from the `!secret` references in the files, not declared,
 * so a key nobody names says so — which is the one thing worth seeing in
 * a list of secrets that has grown over a year.
 */
fun usedByLabel(entry: SecretEntry): String {
    val parts = buildList {
        if (entry.usedByConfigs.isNotEmpty()) add(count(entry.usedByConfigs.size, "config"))
        if (entry.usedByDevices.isNotEmpty()) add(count(entry.usedByDevices.size, "device"))
    }
    return if (parts.isEmpty()) "unused" else parts.joinToString(" · ")
}

private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

/** The table's one sort: by key, the way a file lists them. */
fun sortedSecrets(entries: List<SecretEntry>, ascending: Boolean): List<SecretEntry> =
    if (ascending) entries.sortedBy { it.key } else entries.sortedByDescending { it.key }

/**
 * The values the user has asked to see.
 *
 * A list never carries a value, so there is nothing to unmask locally:
 * every entry here arrived from its own `secret/reveal` request. Changing
 * the scope drops all of them — a value shown for one file is no reason
 * to show one from another.
 */
data class RevealedSecrets(private val values: Map<String, String> = emptyMap()) {
    fun revealed(key: String): String? = values[key]

    fun isRevealed(key: String): Boolean = key in values

    fun with(key: String, value: String): RevealedSecrets = RevealedSecrets(values + (key to value))

    fun hiding(key: String): RevealedSecrets = RevealedSecrets(values - key)

    /** Show it or stop showing it, whichever the row is not doing now. */
    fun toggled(key: String, value: String): RevealedSecrets = if (isRevealed(key)) hiding(key) else with(key, value)

    fun cleared(): RevealedSecrets = RevealedSecrets()
}
