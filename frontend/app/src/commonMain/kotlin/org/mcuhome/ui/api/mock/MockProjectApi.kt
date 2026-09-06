// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.flow.update
import org.mcuhome.ui.api.DoctorReport
import org.mcuhome.ui.api.OptionOrigin
import org.mcuhome.ui.api.ProjectApi
import org.mcuhome.ui.api.ProjectFile
import org.mcuhome.ui.api.ProjectOption
import org.mcuhome.ui.api.PublicKey
import org.mcuhome.ui.api.SaveResult

/**
 * The project as a whole.
 *
 * Options are the interesting part: a value carries the layer that set it,
 * writing puts the value into the project layer, and unsetting drops back
 * to whatever the layer below says. The mock keeps a declared default
 * beside each option so the drop-back has somewhere to land, which is what
 * the real resolver does with the layers underneath.
 */
internal class MockProjectApi(private val context: MockContext) : ProjectApi {
    override suspend fun options(): List<ProjectOption> = context.state.value.options

    override suspend fun setOption(name: String, value: String): ProjectOption {
        val option = requireOption(name)
        if (!option.editable) {
            throw refused(
                "\"$name\" cannot be set from here.",
                hint = "it is a structured option; edit the project file directly",
            )
        }
        if (option.choices.isNotEmpty() && value !in option.choices) {
            throw invalid(
                "\"$value\" is not a value \"$name\" accepts.",
                hint = "one of: ${option.choices.joinToString(", ")}",
            )
        }
        return replace(option.copy(value = value, origin = OptionOrigin.Project))
    }

    override suspend fun unsetOption(name: String): ProjectOption {
        val option = requireOption(name)
        if (option.origin != OptionOrigin.Project) {
            throw refused(
                "\"$name\" is not set in this project's own configuration.",
                hint = "the value comes from the ${option.origin.name.lowercase()} layer",
            )
        }
        return replace(option.copy(value = option.defaultValue, origin = OptionOrigin.Default))
    }

    override suspend fun read(): ProjectFile = ProjectFile(
        path = "mcuhome.yaml",
        text = context.state.value.projectFileText,
        revision = context.state.value.projectFileRevision,
    )

    override suspend fun write(text: String, baseRevision: String): SaveResult {
        val state = context.state.value
        if (state.projectFileRevision != baseRevision) {
            return SaveResult.Conflict(state.projectFileRevision, state.projectFileText)
        }
        val (revision, next) = state.nextRevision()
        context.state.value = next.copy(projectFileText = text, projectFileRevision = revision)
        return SaveResult.Saved(revision)
    }

    override suspend fun doctor(): DoctorReport =
        DoctorReport(checkedAtEpochMillis = context.now(), sections = SAMPLE_DOCTOR_SECTIONS)

    override suspend fun publicKey(): PublicKey = SAMPLE_PUBLIC_KEY

    private fun requireOption(name: String): ProjectOption = context.state.value.options.firstOrNull { it.name == name }
        ?: throw notFound("\"$name\" is not an option MCUHome knows.")

    private fun replace(option: ProjectOption): ProjectOption {
        context.state.update { state ->
            state.copy(options = state.options.map { if (it.name == option.name) option else it })
        }
        return option
    }
}
