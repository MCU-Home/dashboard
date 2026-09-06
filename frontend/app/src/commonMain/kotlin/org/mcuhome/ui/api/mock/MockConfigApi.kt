// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import org.mcuhome.ui.api.ConfigApi
import org.mcuhome.ui.api.ConfigChanged
import org.mcuhome.ui.api.ConfigUserResult
import org.mcuhome.ui.api.ConfigUsersReport
import org.mcuhome.ui.api.SaveResult
import org.mcuhome.ui.api.SecretReference
import org.mcuhome.ui.api.SecretScope
import org.mcuhome.ui.api.SharedConfigFile
import org.mcuhome.ui.api.SharedConfigSummary

private val CONFIG_FILE_NAME = Regex("^[a-z][a-z0-9-]*\\.yaml$")

private val NEW_CONFIG_TEXT = """
    # A shared configuration. Devices pull it in with
    # `!include ../../configs/<this file>`; validation runs on the devices
    # that use it, because half of a fragment only makes sense inside one.
""".trimIndent()

/**
 * The shared configuration files under `configs/`.
 *
 * A fragment is never validated on its own — `validateUsers` runs the
 * device validation over every device that includes it, which is the only
 * check that means anything for a file that is not a whole configuration.
 */
internal class MockConfigApi(private val context: MockContext) : ConfigApi {
    override suspend fun list(): List<SharedConfigSummary> =
        context.state.value.configs.map { summary(context.state.value, it) }

    override suspend fun read(fileName: String): SharedConfigFile {
        val state = context.state.value
        val config = context.requireConfig(fileName)
        val projectSecrets = state.secrets[SecretScope.Project].orEmpty()
        return SharedConfigFile(
            summary = summary(state, config),
            text = config.text,
            revision = config.revision,
            referencedSecrets = referencedSecrets(config.text).map {
                SecretReference(key = it, set = it in projectSecrets)
            },
        )
    }

    override suspend fun write(
        fileName: String,
        text: String,
        baseRevision: String,
    ): SaveResult {
        val config = context.requireConfig(fileName)
        if (config.revision != baseRevision) return SaveResult.Conflict(config.revision, config.text)
        val (revision, next) = context.state.value.nextRevision()
        val updated = config.copy(text = text, revision = revision, changedAtEpochMillis = context.now())
        context.state.value = next.withConfig(updated)
        context.emit(ConfigChanged(fileName))
        return SaveResult.Saved(revision)
    }

    override suspend fun new(fileName: String): SharedConfigSummary {
        if (!CONFIG_FILE_NAME.matches(fileName)) {
            throw invalid(
                "\"$fileName\" is not a usable configuration file name.",
                hint = "lowercase letters, digits and hyphens, ending in .yaml",
            )
        }
        if (context.state.value.config(fileName) != null) {
            throw refused("There is already a configuration called \"$fileName\".")
        }
        val (revision, next) = context.state.value.nextRevision()
        val config = MockConfigFile(fileName, NEW_CONFIG_TEXT, revision, context.now())
        context.state.value = next.copy(configs = next.configs + config)
        context.emit(ConfigChanged(fileName))
        return summary(context.state.value, config)
    }

    override suspend fun validateUsers(fileName: String): ConfigUsersReport {
        val state = context.state.value
        val config = context.requireConfig(fileName)
        val users = state.devices.filter { config.fileName in includedFiles(it.text) }
        return ConfigUsersReport(
            fileName = fileName,
            users = users.map { ConfigUserResult(it.name, state.validate(it, it.text, context.now())) },
        )
    }
}

private fun summary(state: MockState, config: MockConfigFile) = SharedConfigSummary(
    fileName = config.fileName,
    path = config.path,
    usedByDevices = state.devices.filter { config.fileName in includedFiles(it.text) }.map { it.name },
    changedAtEpochMillis = config.changedAtEpochMillis,
)
