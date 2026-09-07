// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildRunState
import org.mcuhome.ui.api.BuildSnapshot
import org.mcuhome.ui.api.BuildStage
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.BuildStatus
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.ConfigStatus
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.PairingSummary
import org.mcuhome.ui.api.StageState
import org.mcuhome.ui.api.StageStatus
import org.mcuhome.ui.panel.BuildRun
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceRailSectionTest {
    @Test
    fun a_label_is_the_block_and_what_it_reports() {
        assertEquals("Build — compiling", railSectionLabel(DeviceRailSection.Build, "compiling"))
        assertEquals("Matter pairing — present", railSectionLabel(DeviceRailSection.Pairing, "present"))
    }

    @Test
    fun the_config_block_says_what_its_pill_says() {
        assertEquals("valid", configSectionState(ConfigStatus(ConfigState.Valid)))
        assertEquals("1 error", configSectionState(ConfigStatus(ConfigState.Errors, errorCount = 1)))
        assertEquals("2 warnings", configSectionState(ConfigStatus(ConfigState.Warnings, warningCount = 2)))
        assertEquals("not checked", configSectionState(ConfigStatus(ConfigState.Unknown)))
    }

    @Test
    fun the_build_block_names_the_running_stage_before_the_last_result() {
        val running = BuildRun(snapshot = snapshot(BuildStage.Compile))
        assertEquals("compiling", buildSectionState(running, BuildStatus(BuildState.Built)))
        assertEquals("built", buildSectionState(BuildRun(), BuildStatus(BuildState.Built)))
        assertEquals("failed", buildSectionState(BuildRun(), BuildStatus(BuildState.Failed)))
        assertEquals("never built", buildSectionState(BuildRun(), BuildStatus(BuildState.NeverBuilt)))
    }

    @Test
    fun a_stage_ending_in_an_e_does_not_keep_it() {
        assertEquals("linking", buildRunningLabel(BuildRun(snapshot = snapshot(BuildStage.Link))))
        assertEquals("configuring", buildRunningLabel(BuildRun(snapshot = snapshot(BuildStage.Configure))))
        assertEquals("building", buildRunningLabel(BuildRun()))
    }

    @Test
    fun the_artifacts_block_counts_files_and_admits_when_there_are_none() {
        assertEquals("none yet", artifactsSectionState(0))
        assertEquals("1 file", artifactsSectionState(1))
        assertEquals("3 files", artifactsSectionState(3))
    }

    @Test
    fun the_pairing_block_says_whether_there_are_credentials() {
        assertEquals("present", pairingSectionState(PairingSummary(present = true, maskedDiscriminator = "••••")))
        assertEquals("none yet", pairingSectionState(PairingSummary(present = false, maskedDiscriminator = "")))
        assertEquals("none yet", pairingSectionState(null))
    }

    @Test
    fun the_diagnostics_block_reports_the_worst_it_found() {
        assertEquals("nothing to report", diagnosticsSectionState(emptyList()))
        assertEquals("1 warning", diagnosticsSectionState(listOf(finding(DiagnosticSeverity.Warning))))
        assertEquals(
            "1 error",
            diagnosticsSectionState(listOf(finding(DiagnosticSeverity.Warning), finding(DiagnosticSeverity.Error))),
        )
        assertEquals("2 notes", diagnosticsSectionState(List(2) { finding(DiagnosticSeverity.Info) }))
    }

    private fun snapshot(stage: BuildStage) = BuildSnapshot(
        buildId = "b1",
        device = "garage-door",
        method = BuildMethod.Local,
        state = BuildRunState.Running,
        stages = listOf(StageStatus(stage = stage, state = StageState.Running)),
        startedAtEpochMillis = 0,
    )

    private fun finding(severity: DiagnosticSeverity) = Diagnostic(severity = severity, message = "something")
}
