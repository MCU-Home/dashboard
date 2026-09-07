// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import org.mcuhome.ui.api.OptionOrigin
import org.mcuhome.ui.api.ProjectOption

/** The four faces of the project screen. */
enum class ProjectTab(val label: String) {
    Options("Options"),
    Yaml("Edit as YAML"),
    Boards("Boards"),
    Doctor("Doctor"),
}

/**
 * What the last column of the options table offers.
 *
 * The screen writes one layer — the project's own configuration file —
 * and can therefore only take back what it put there. A value from
 * another layer is not edited where it lives; it is *overridden* by
 * writing the same value into the project layer, from where it can then
 * be reset again. An option nobody set anywhere has nothing to promote:
 * typing a value into it is what makes it a project value.
 */
enum class OptionAction(val label: String) {
    /** The project layer sets it; dropping it lets the layer below take over. */
    Reset("reset"),

    /** Another layer sets it; writing the same value here makes it the project's. */
    Override("override"),

    /** Nothing to do from here. */
    None("—"),
}

fun optionAction(option: ProjectOption): OptionAction = when {
    !option.editable -> OptionAction.None
    option.origin == OptionOrigin.Project -> OptionAction.Reset
    option.value == null -> OptionAction.None
    else -> OptionAction.Override
}

/**
 * The layer in force, as the "Set by" column names it. An option with no
 * value anywhere is set by nobody, and says so with a dash rather than
 * with the name of a layer that is not carrying it.
 */
fun originLabel(option: ProjectOption): String = if (option.value == null) "—" else option.origin.name.lowercase()

/** True where the value is the project's own, which the column marks in the accent. */
fun isProjectValue(option: ProjectOption): Boolean = option.value != null && option.origin == OptionOrigin.Project

/** What the value field shows when the option holds nothing. */
const val UNSET_OPTION_PLACEHOLDER: String = "— not set"
