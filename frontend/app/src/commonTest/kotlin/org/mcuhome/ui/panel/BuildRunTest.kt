// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import org.mcuhome.ui.api.BuildChanged
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildOutputAppended
import org.mcuhome.ui.api.BuildRunState
import org.mcuhome.ui.api.BuildSnapshot
import org.mcuhome.ui.api.BuildStage
import org.mcuhome.ui.api.OutputLevel
import org.mcuhome.ui.api.OutputLine
import org.mcuhome.ui.api.Progress
import org.mcuhome.ui.api.StageState
import org.mcuhome.ui.api.StageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildRunTest {
    @Test
    fun a_run_that_saw_nothing_yet_shows_nothing() {
        val empty = BuildRun()
        assertNull(empty.snapshot)
        assertFalse(empty.running)
        assertEquals(0f, empty.progressFraction())
    }

    @Test
    fun output_lines_are_appended_in_the_order_they_arrive() {
        val run = BuildRun()
            .applied(BuildOutputAppended("b-1", listOf(line("first"))))
            .applied(BuildOutputAppended("b-1", listOf(line("second"), line("third"))))
        assertEquals(listOf("first", "second", "third"), run.lines.map { it.text })
    }

    @Test
    fun the_last_snapshot_wins_and_says_whether_the_build_still_runs() {
        val run = BuildRun()
            .applied(BuildChanged(snapshot(BuildRunState.Running)))
            .applied(BuildChanged(snapshot(BuildRunState.Succeeded)))
        assertEquals(BuildRunState.Succeeded, run.snapshot?.state)
        assertFalse(run.running)
        assertTrue(BuildRun().applied(BuildChanged(snapshot(BuildRunState.Running))).running)
    }

    @Test
    fun progress_counts_the_stages_that_are_over_plus_the_one_in_flight() {
        val run = BuildRun().applied(
            BuildChanged(
                snapshot(
                    BuildRunState.Running,
                    listOf(
                        StageStatus(BuildStage.Generate, StageState.Done),
                        StageStatus(BuildStage.Configure, StageState.Done),
                        StageStatus(BuildStage.Compile, StageState.Running, progress = Progress(190, 380)),
                        StageStatus(BuildStage.Link, StageState.Pending),
                        StageStatus(BuildStage.Sign, StageState.Pending),
                    ),
                ),
            ),
        )
        assertEquals(0.5f, run.progressFraction())
        assertEquals(Progress(190, 380), run.runningProgress())
    }

    @Test
    fun a_skipped_stage_counts_as_finished() {
        val run = BuildRun().applied(
            BuildChanged(
                snapshot(
                    BuildRunState.Succeeded,
                    listOf(
                        StageStatus(BuildStage.Generate, StageState.Done),
                        StageStatus(BuildStage.Sign, StageState.Skipped),
                    ),
                ),
            ),
        )
        assertEquals(1f, run.progressFraction())
        assertNull(run.runningProgress())
    }

    @Test
    fun a_stage_is_labelled_by_what_it_knows_about_itself() {
        assertEquals("compile 142 / 380", stageLabel(BuildStage.Compile, Progress(142, 380), null))
        assertEquals("configure 6.1s", stageLabel(BuildStage.Configure, null, 6100))
        assertEquals("link", stageLabel(BuildStage.Link, null, null))
    }

    @Test
    fun a_stage_duration_keeps_one_decimal_only_while_it_says_something() {
        assertEquals("0.4s", formatStageDuration(400))
        assertEquals("6.1s", formatStageDuration(6100))
        assertEquals("12s", formatStageDuration(12_400))
    }

    private fun line(text: String) = OutputLine(0, text, OutputLevel.Plain)

    private fun snapshot(state: BuildRunState, stages: List<StageStatus> = emptyList()) = BuildSnapshot(
        buildId = "b-1",
        device = "garage-door",
        method = BuildMethod.Local,
        state = state,
        stages = stages,
        startedAtEpochMillis = 0,
    )
}
