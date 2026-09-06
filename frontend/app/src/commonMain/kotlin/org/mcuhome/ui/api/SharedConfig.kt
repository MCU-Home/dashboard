// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.Serializable

/**
 * One file in the project's `configs/` directory, as the file list shows it.
 *
 * A shared configuration is not validated on its own — it is a fragment a
 * device pulls in with `!include`, and half of it only makes sense inside
 * the device that includes it. [usedByDevices] is therefore both the "used
 * by n devices" label and the set of devices `config/validate-users`
 * checks.
 */
@Serializable
data class SharedConfigSummary(
    val fileName: String,
    val path: String,
    val usedByDevices: List<String> = emptyList(),
    val changedAtEpochMillis: Long,
)

/** A shared configuration file with its text and everything the rail shows. */
@Serializable
data class SharedConfigFile(
    val summary: SharedConfigSummary,
    val text: String,
    val revision: String,
    /** The `!secret` keys the file refers to, and whether each is set. */
    val referencedSecrets: List<SecretReference> = emptyList(),
)

/** One `!secret` reference out of a configuration file. */
@Serializable
data class SecretReference(val key: String, val set: Boolean)

/**
 * What validating the users of one shared configuration found: one
 * report per device that includes it.
 */
@Serializable
data class ConfigUsersReport(val fileName: String, val users: List<ConfigUserResult> = emptyList()) {
    val ok: Boolean get() = users.all { it.report.ok }
}

/** One device's answer inside a [ConfigUsersReport]. */
@Serializable
data class ConfigUserResult(val device: String, val report: ValidationReport)
