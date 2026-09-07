// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.job

import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildStage
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobKind
import org.mcuhome.ui.api.JobState
import org.mcuhome.ui.api.Progress
import kotlin.test.Test
import kotlin.test.assertEquals

/** 2026-09-07 12:52:06 UTC — the instant the sample project is frozen at. */
private const val NOW = 1_788_785_526_000L

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

private val RUNNING_BUILD = Job(
    id = "j-1",
    kind = JobKind.Build,
    device = "garage-door",
    state = JobState.Running,
    startedAtEpochMillis = NOW - MINUTE,
    progress = Progress(142, 380),
    stage = BuildStage.Compile,
    method = BuildMethod.Local,
    buildId = "b-1",
)

private val FINISHED_BUILD = Job(
    id = "j-past-1",
    kind = JobKind.Build,
    device = "kitchen-sensor",
    state = JobState.Finished,
    startedAtEpochMillis = NOW - 11 * MINUTE - 252_000,
    finishedAtEpochMillis = NOW - 11 * MINUTE,
    method = BuildMethod.Local,
    summary = "firmware.signed.bin, kitchen-sensor.ota",
    buildId = "b-kitchen-sensor-1",
)

private val FAILED_VALIDATE = Job(
    id = "j-past-2",
    kind = JobKind.Validate,
    device = "hallway-light",
    state = JobState.Failed,
    startedAtEpochMillis = NOW - 14 * MINUTE,
    finishedAtEpochMillis = NOW - 14 * MINUTE,
    summary = "2 errors in main.yaml",
)

private val FINISHED_FLASH = Job(
    id = "j-past-3",
    kind = JobKind.Flash,
    device = "bench-node",
    state = JobState.Finished,
    startedAtEpochMillis = NOW - 18 * HOUR - 40 * MINUTE - 30_000,
    finishedAtEpochMillis = NOW - 18 * HOUR - 40 * MINUTE,
    summary = "recovery · /dev/ttyACM1",
)

/** The two lines the jobs popover writes for an entry, and the order it lists them in. */
class JobTextTest {
    @Test
    fun whatIsRunningComesFirstAndTheRestIsNewestFirst() {
        val ordered = orderedJobs(listOf(FINISHED_FLASH, FAILED_VALIDATE, FINISHED_BUILD, RUNNING_BUILD))
        assertEquals(listOf("j-1", "j-past-1", "j-past-2", "j-past-3"), ordered.map { it.id })
    }

    @Test
    fun theOrderDoesNotDependOnTheOrderItWasGivenIn() {
        val jobs = listOf(FINISHED_FLASH, FAILED_VALIDATE, FINISHED_BUILD, RUNNING_BUILD)
        assertEquals(orderedJobs(jobs), orderedJobs(jobs.reversed()))
    }

    @Test
    fun anEntrySaysWhichDeviceAndWhatIsBeingDoneToIt() {
        assertEquals("garage-door · build", jobTitle(RUNNING_BUILD))
        assertEquals("hallway-light · validate", jobTitle(FAILED_VALIDATE))
        assertEquals("bench-node · flash", jobTitle(FINISHED_FLASH))
    }

    @Test
    fun aRunningBuildSaysHowFarItHasGotAndWhenItStarted() {
        assertEquals("compile 142 / 380 · local · started 12:51", jobSubtitle(RUNNING_BUILD, NOW))
    }

    @Test
    fun aRunningJobWithoutAStageSaysOnlyWhatItKnows() {
        val job = RUNNING_BUILD.copy(stage = null, progress = null, method = null)
        assertEquals("started 12:51", jobSubtitle(job, NOW))
    }

    @Test
    fun aFinishedBuildSaysWhenItEndedHowLongItTookAndWhatItLeftBehind() {
        assertEquals(
            "finished 12:41 · 4 min 12 s · firmware.signed.bin, kitchen-sensor.ota",
            jobSubtitle(FINISHED_BUILD, NOW),
        )
    }

    @Test
    fun aFailedJobThatTookNoTimeStatesNoDuration() {
        assertEquals("failed 12:38 · 2 errors in main.yaml", jobSubtitle(FAILED_VALIDATE, NOW))
    }

    @Test
    fun anEntryFromAnotherDayCarriesThatDay() {
        assertEquals("finished yesterday 18:12 · 30 s · recovery · /dev/ttyACM1", jobSubtitle(FINISHED_FLASH, NOW))
    }

    @Test
    fun aCancelledJobSaysSo() {
        val cancelled = RUNNING_BUILD.copy(
            state = JobState.Cancelled,
            finishedAtEpochMillis = NOW,
            summary = "The build was cancelled.",
        )
        assertEquals("cancelled 12:52 · 1 min 0 s · The build was cancelled.", jobSubtitle(cancelled, NOW))
    }

    @Test
    fun everyKindOfWorkHasAWord() {
        assertEquals(
            listOf("build", "validate", "sign", "flash", "first-time setup", "clean", "pairing"),
            JobKind.entries.map { jobKindLabel(it) },
        )
    }
}
