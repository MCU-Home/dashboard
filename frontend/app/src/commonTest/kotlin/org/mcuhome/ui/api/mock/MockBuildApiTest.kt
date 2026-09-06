// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.BuildChanged
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildOutputAppended
import org.mcuhome.ui.api.BuildRunState
import org.mcuhome.ui.api.BuildStage
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.JobState
import org.mcuhome.ui.api.OutputLevel
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.api.StageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A simulated build, from the first stage to the artifacts it leaves
 * behind — and the same build stopped halfway.
 */
class MockBuildApiTest {
    @Test
    fun aBuildRunsItsFiveStagesInOrderAndEndsSucceeded() = runTest {
        val api = mockApi()
        val started = api.build.start("balcony-climate", BuildMethod.Local)

        val events = api.build.stream(started.buildId).toList()

        val snapshots = events.filterIsInstance<BuildChanged>().map { it.snapshot }
        assertEquals(BuildRunState.Succeeded, snapshots.last().state)
        assertEquals(
            listOf(BuildStage.Generate, BuildStage.Configure, BuildStage.Compile, BuildStage.Link, BuildStage.Sign),
            snapshots.mapNotNull { it.currentStage }.distinct(),
        )
        assertTrue(snapshots.last().stages.all { it.state == StageState.Done })
    }

    @Test
    fun theCompileStageCountsUpToItsTotal() = runTest {
        val api = mockApi()
        val started = api.build.start("balcony-climate", BuildMethod.Local)

        val events = api.build.stream(started.buildId).toList()

        val compile = events.filterIsInstance<BuildChanged>()
            .mapNotNull { snapshot -> snapshot.snapshot.stages.first { it.stage == BuildStage.Compile }.progress }
        assertEquals(COMPILE_STEPS, compile.last().total)
        assertEquals(COMPILE_STEPS, compile.last().done)
        assertEquals(compile.map { it.done }.sorted(), compile.map { it.done })
    }

    @Test
    fun theBuildStreamsOutputIncludingACompilerWarning() = runTest {
        val api = mockApi()
        val started = api.build.start("balcony-climate", BuildMethod.Local)

        val lines = api.build.stream(started.buildId).toList()
            .filterIsInstance<BuildOutputAppended>()
            .flatMap { it.lines }

        assertTrue(lines.first().text.startsWith("-- Resolving balcony-climate"))
        assertTrue(lines.any { it.level == OutputLevel.Warning })
        assertTrue(lines.any { "Linking C executable" in it.text })
    }

    @Test
    fun aFinishedBuildLeavesArtifactsAndMarksTheDeviceBuiltAndSigned() = runTest {
        val api = mockApi()
        val started = api.build.start("balcony-climate", BuildMethod.Local)
        api.build.stream(started.buildId).toList()

        val artifacts = api.build.artifacts(started.buildId)
        assertEquals(
            listOf("firmware.signed.bin", "balcony-climate.ota", "build-report.json"),
            artifacts.map { it.fileName },
        )

        val device = api.device.list().first { it.name == "balcony-climate" }
        assertEquals(BuildState.Built, device.build.state)
        assertEquals(SignedState.Signed, device.signed)
        assertNotNull(api.device.get("balcony-climate").lastGoodBuild)
    }

    @Test
    fun anArtifactIsDownloadedOverAnAddressRatherThanTheCommandChannel() = runTest {
        val api = mockApi()
        val started = api.build.start("balcony-climate", BuildMethod.Local)
        api.build.stream(started.buildId).toList()

        val download = api.build.download(started.buildId, "firmware.signed.bin")

        assertEquals("/api/build/${started.buildId}/artifact/firmware.signed.bin", download.url)
        assertEquals("firmware.signed.bin", download.fileName)
    }

    @Test
    fun cancellingStopsTheBuildAndPutsTheDeviceBackAsItWas() = runTest {
        val api = mockApi()
        val before = api.device.list().first { it.name == "kitchen-sensor" }
        val started = api.build.start("kitchen-sensor", BuildMethod.Local)

        // Wait for the build to be under way, then stop it.
        api.build.stream(started.buildId).filterIsInstance<BuildOutputAppended>().first()
        api.build.cancel(started.buildId)
        val events = api.build.stream(started.buildId).toList()

        val last = events.filterIsInstance<BuildChanged>().last().snapshot
        assertEquals(BuildRunState.Cancelled, last.state)
        assertTrue(last.stages.none { it.state == StageState.Running })

        val after = api.device.list().first { it.name == "kitchen-sensor" }
        assertEquals(before.build.state, after.build.state)
        assertEquals(before.signed, after.signed)
    }

    @Test
    fun aCancelledBuildIsACancelledJob() = runTest {
        val api = mockApi()
        val started = api.build.start("kitchen-sensor", BuildMethod.Local)
        api.build.stream(started.buildId).filterIsInstance<BuildOutputAppended>().first()
        api.build.cancel(started.buildId)
        api.build.stream(started.buildId).toList()

        val job = api.job.list().first { it.buildId == started.buildId }
        assertEquals(JobState.Cancelled, job.state)
    }

    @Test
    fun onlyOneBuildRunsAtATime() = runTest {
        val api = mockApi()
        api.build.start("balcony-climate", BuildMethod.Local)

        val error = assertFailsWith<ApiException> { api.build.start("bench-node", BuildMethod.Remote) }

        assertTrue("already running" in error.error.message)
    }

    @Test
    fun theMockStartsTheBuildTheDesignsTableShows() = runTest {
        val api = mockApi(startingBuild = true)

        val garageDoor = api.device.list().first { it.name == "garage-door" }
        assertEquals(BuildState.Building, garageDoor.build.state)
        assertNotNull(garageDoor.build.buildId)
        assertEquals(1, api.job.list().count { it.state == JobState.Running })
    }

    @Test
    fun clearingFinishedJobsLeavesTheRunningOne() = runTest {
        val api = mockApi(startingBuild = true)

        val cleared = api.job.clearFinished()

        assertEquals(3, cleared.size)
        assertEquals(1, api.job.list().size)
        assertEquals(JobState.Running, api.job.list().single().state)
    }
}
