// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.BuildStatus
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.ConfigStatus
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.PairingSummary
import org.mcuhome.ui.panel.BuildRun

/**
 * The blocks of the status rail, in the order the rail draws them.
 *
 * The collapsed rail shows one icon per block; naming them here is what
 * lets an icon say which block it belongs to, so that pressing it can
 * open the rail at that block instead of merely opening it.
 */
enum class DeviceRailSection(val title: String) {
    Config("Config"),
    Build("Build"),
    Artifacts("Artifacts"),
    Pairing("Matter pairing"),
    Diagnostics("Diagnostics"),
}

/**
 * What one icon of the collapsed rail says on hover: which block it
 * stands for, and what that block currently reports — "Build —
 * compiling", "Diagnostics — 1 warning".
 *
 * A strip of five icons with three coloured dots says that something is
 * going on but not what; the label is the sentence the open rail would
 * have shown in its place.
 */
fun railSectionLabel(section: DeviceRailSection, state: String): String = "${section.title} — $state"

/** The Config block in one word: what its pill says. */
fun configSectionState(config: ConfigStatus): String = when (config.state) {
    ConfigState.Valid -> "valid"
    ConfigState.Errors -> countLabel(config.errorCount, "error")
    ConfigState.Warnings -> countLabel(config.warningCount, "warning")
    ConfigState.Unknown -> "not checked"
}

/** The Build block in one word: the running stage, or what the last build did. */
fun buildSectionState(build: BuildRun, status: BuildStatus): String = when {
    build.running || status.state == BuildState.Building -> buildRunningLabel(build)
    status.state == BuildState.Built -> "built"
    status.state == BuildState.Failed -> "failed"
    else -> "never built"
}

/** The Artifacts block in one word: how many files the last good build left. */
fun artifactsSectionState(count: Int): String = if (count == 0) "none yet" else countLabel(count, "file")

/** The Matter pairing block in one word: whether there are credentials. */
fun pairingSectionState(pairing: PairingSummary?): String = if (pairing?.present == true) "present" else "none yet"

/** The Diagnostics block in one word: the worst thing the validator found, counted. */
fun diagnosticsSectionState(diagnostics: List<Diagnostic>): String {
    val errors = diagnostics.count { it.severity == DiagnosticSeverity.Error }
    val warnings = diagnostics.count { it.severity == DiagnosticSeverity.Warning }
    return when {
        errors > 0 -> countLabel(errors, "error")
        warnings > 0 -> countLabel(warnings, "warning")
        diagnostics.isEmpty() -> "nothing to report"
        else -> countLabel(diagnostics.size, "note")
    }
}

/**
 * The stage a running build is in, as the rail's pill says it:
 * "compiling", "linking", "signing".
 */
fun buildRunningLabel(build: BuildRun): String = build.snapshot?.currentStage?.name?.lowercase()?.let { stage ->
    if (stage.endsWith("e")) "${stage.dropLast(1)}ing" else "${stage}ing"
} ?: "building"
