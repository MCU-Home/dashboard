// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

/** How serious a diagnostic is; it decides the color of marker and underline. */
enum class DiagnosticSeverity { Error, Warning, Info }

/**
 * One finding the validator reports about the open document, placed on a
 * line. Lines are counted from one, the way the validator and the gutter
 * count them.
 */
data class EditorDiagnostic(
    val line: Int,
    val message: String,
    val severity: DiagnosticSeverity = DiagnosticSeverity.Warning,
)
