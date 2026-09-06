// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One problem with one place in one configuration file.
 *
 * The fields are the ones the builder reports for a configuration error —
 * message, file relative to the project, line, column, the configuration
 * key it is about, a fix hint, and the error's own kind — plus a
 * [severity], which the builder does not have: it raises errors and
 * reports warnings through a separate channel, and the interface needs
 * both in one list to draw them in the same gutter.
 *
 * [line] and [column] are one-based, as an editor counts them, and are
 * null for a problem with no place to point at (a missing secrets file).
 */
@Serializable
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val hint: String? = null,
    val key: String? = null,
    val kind: String? = null,
)

/** How bad a [Diagnostic] is. */
@Serializable
enum class DiagnosticSeverity {
    @SerialName("error")
    Error,

    @SerialName("warning")
    Warning,

    @SerialName("info")
    Info,
}

/**
 * What checking one configuration found.
 *
 * [ok] is false exactly when [diagnostics] holds at least one
 * [DiagnosticSeverity.Error]; warnings alone leave a configuration valid,
 * which is what the device table's "1 warning" pill means.
 */
@Serializable
data class ValidationReport(
    val ok: Boolean,
    val file: String,
    val diagnostics: List<Diagnostic> = emptyList(),
    val checkedAtEpochMillis: Long,
) {
    val errorCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.Error }
    val warningCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.Warning }
}
