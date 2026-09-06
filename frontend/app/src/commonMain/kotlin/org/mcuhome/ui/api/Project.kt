// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of the Project screen's options table.
 *
 * The builder resolves its options over layered configuration files and
 * reports, for every option, the value in force and the layer that supplied
 * it. Both travel here: [value] is what is in force, [origin] is the layer
 * — which is what makes "reset" and "override" meaningful actions rather
 * than guesses.
 *
 * [name] is the option's configuration key, spelled exactly as a
 * configuration file spells it; [label] is what the table prints.
 */
@Serializable
data class ProjectOption(
    val name: String,
    val label: String,
    val help: String,
    val kind: OptionKind,
    val value: String? = null,
    val origin: OptionOrigin,
    val defaultValue: String? = null,
    /** The values a vocabulary option accepts; empty means free text. */
    val choices: List<String> = emptyList(),
    /**
     * False for options that no configuration file may set — the two
     * bootstrap options, and the structured ones the interface has no
     * editor for.
     */
    val editable: Boolean = true,
)

/** What kind of value an option holds. The builder's own option kinds. */
@Serializable
enum class OptionKind {
    @SerialName("string")
    Text,

    @SerialName("path")
    Path,

    /** An ordered list of paths. */
    @SerialName("paths")
    Paths,

    @SerialName("integer")
    Integer,

    /** Named builders — a nested map, files only. */
    @SerialName("builders")
    Builders,

    /** Package registries by domain — a nested map, files only. */
    @SerialName("registry")
    Registry,
}

/**
 * Which layer set an option's value, in ascending precedence.
 *
 * The Project screen writes to [Project] and can only reset what it wrote;
 * a value from [User] or [System] is overridden by writing the project
 * layer, never by editing the other file.
 */
@Serializable
enum class OptionOrigin {
    /** Nothing set it; the declared default is in force. */
    @SerialName("default")
    Default,

    @SerialName("system")
    System,

    @SerialName("user")
    User,

    @SerialName("project")
    Project,

    @SerialName("environment")
    Environment,

    @SerialName("arguments")
    Arguments,
}

/** The project file itself, for the "Edit as YAML" tab. */
@Serializable
data class ProjectFile(val path: String, val text: String, val revision: String)

/** What `mcuhome doctor` reports, grouped as the Doctor tab prints it. */
@Serializable
data class DoctorReport(val checkedAtEpochMillis: Long, val sections: List<DoctorSection> = emptyList()) {
    val ok: Boolean get() = sections.all { section -> section.checks.none { it.status == DoctorStatus.Failed } }
}

/** One group of the doctor report. */
@Serializable
data class DoctorSection(val title: String, val checks: List<DoctorCheck> = emptyList())

/** One thing the doctor looked at. */
@Serializable
data class DoctorCheck(val name: String, val status: DoctorStatus, val message: String, val hint: String? = null)

/** How one doctor check came out. */
@Serializable
enum class DoctorStatus {
    @SerialName("ok")
    Ok,

    @SerialName("warning")
    Warning,

    @SerialName("failed")
    Failed,

    @SerialName("skipped")
    Skipped,
}

/**
 * The public half of the project's firmware signing key.
 *
 * The public half only: the private key never leaves the project tree, and
 * nothing in this API can read it.
 */
@Serializable
data class PublicKey(val pem: String, val keyFile: String, val algorithm: String)
