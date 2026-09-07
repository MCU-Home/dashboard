// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.BuildChanged
import org.mcuhome.ui.api.BuildEvent
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildOutputAppended
import org.mcuhome.ui.api.BuildRunState
import org.mcuhome.ui.api.BuildSnapshot
import org.mcuhome.ui.api.BuildStage
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.BuildStatus
import org.mcuhome.ui.api.BuildSummary
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobKind
import org.mcuhome.ui.api.JobState
import org.mcuhome.ui.api.OutputLine
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.api.StageState
import org.mcuhome.ui.api.StageStatus

/** How many events a build keeps for a screen that opens it late. */
private const val BUILD_EVENT_REPLAY = 512

/** What the sample project's `jobs` option resolves to. */
internal const val SAMPLE_PARALLEL_JOBS = 8

/** One simulated build: its snapshot, its stream, and whether it was told to stop. */
private class MockBuildRun(
    var snapshot: BuildSnapshot,
    val jobId: String,
    val previousStatus: BuildStatus,
    val previousSigned: SignedState,
) {
    val events = MutableSharedFlow<BuildEvent>(replay = BUILD_EVENT_REPLAY, extraBufferCapacity = BUILD_EVENT_REPLAY)
    var cancelRequested = false
}

/**
 * The mock's builds.
 *
 * A build is a loop over [buildScript]: every tick moves the stage row,
 * appends output and waits for as long as the context's speed factor says.
 * Nothing is stopped with coroutine cancellation — a cancel sets a flag the
 * loop reads between ticks — so a cancelled build still writes its own
 * ending, exactly as a real one that is asked to stop does.
 *
 * Only one build runs at a time, which is the rule the design states.
 */
internal class MockBuildEngine(private val context: MockContext, private val scope: CoroutineScope) {
    private val runs = mutableMapOf<String, MockBuildRun>()

    val running: BuildSnapshot?
        get() = runs.values.map { it.snapshot }.firstOrNull { it.state == BuildRunState.Running }

    fun start(
        deviceName: String,
        method: BuildMethod,
        signing: Boolean = true,
    ): BuildSnapshot {
        val device = context.requireDevice(deviceName)
        running?.let {
            throw refused(
                "A build is already running for ${it.device}.",
                hint = "wait for it to finish, or cancel it from the jobs list",
            )
        }
        val buildId = context.nextId("b")
        val run = MockBuildRun(
            snapshot = initialSnapshot(buildId, device, method, context.now()),
            jobId = context.nextId("j"),
            previousStatus = device.build,
            previousSigned = device.signed,
        )
        runs[buildId] = run

        val building = device.copy(
            build = device.build.copy(
                state = BuildState.Building,
                method = method,
                buildId = buildId,
                progress = null,
            ),
        )
        context.state.update { it.withDevice(building) }
        context.deviceChanged(building)
        context.addJob(journal(run, JobState.Running))
        publish(run)
        scope.launch { execute(run, buildScript(device.name, device.board, method, signing)) }
        return run.snapshot
    }

    fun cancel(buildId: String) {
        val run = runs[buildId] ?: throw notFound("There is no build with the id \"$buildId\".")
        if (run.snapshot.state != BuildRunState.Running) throw refused("That build is already over.")
        run.cancelRequested = true
    }

    fun status(buildId: String): BuildSnapshot =
        runs[buildId]?.snapshot ?: throw notFound("There is no build with the id \"$buildId\".")

    @OptIn(ExperimentalCoroutinesApi::class)
    fun stream(buildId: String): Flow<BuildEvent> {
        val run = runs[buildId] ?: throw notFound("There is no build with the id \"$buildId\".")
        return run.events.transformWhile { event ->
            emit(event)
            !(event is BuildChanged && event.snapshot.state != BuildRunState.Running)
        }
    }

    private suspend fun execute(run: MockBuildRun, script: List<BuildTick>) {
        for (tick in script) {
            if (run.cancelRequested) {
                finish(run, BuildRunState.Cancelled, "The build was cancelled.")
                return
            }
            context.pause(tick.durationMillis)
            if (tick.lines.isNotEmpty()) {
                val at = context.now()
                run.events.tryEmit(
                    BuildOutputAppended(run.snapshot.buildId, tick.lines.map { OutputLine(at, it.text, it.level) }),
                )
            }
            advanceStage(run, tick)
            publish(run)
        }
        finish(run, BuildRunState.Succeeded, "Build finished.")
    }

    private fun advanceStage(run: MockBuildRun, tick: BuildTick) {
        run.snapshot = run.snapshot.copy(
            stages = run.snapshot.stages.map { status ->
                when {
                    status.stage != tick.stage -> status

                    tick.completesStage -> status.copy(
                        state = StageState.Done,
                        durationMillis = tick.durationMillis,
                        progress = tick.progress,
                    )

                    else -> status.copy(state = StageState.Running, progress = tick.progress)
                }
            },
        )
        val device = context.state.value.device(run.snapshot.device) ?: return
        // The device table draws the counted progress beside its
        // "building" pill, and it draws it from the device topic, so the
        // device is announced as changed on every tick rather than only
        // when the build starts and ends.
        val advanced = device.copy(build = device.build.copy(progress = tick.progress))
        context.state.update { it.withDevice(advanced) }
        context.deviceChanged(advanced)
        context.updateJob(journal(run, JobState.Running).copy(progress = tick.progress, stage = tick.stage))
    }

    private fun finish(
        run: MockBuildRun,
        state: BuildRunState,
        message: String,
    ) {
        val finishedAt = context.now()
        val succeeded = state == BuildRunState.Succeeded
        val artifacts = if (succeeded) sampleArtifacts(run.snapshot.device) else emptyList()
        run.snapshot = run.snapshot.copy(
            state = state,
            finishedAtEpochMillis = finishedAt,
            artifacts = artifacts,
            message = message,
            stages = run.snapshot.stages.map {
                if (it.state == StageState.Running) it.copy(state = StageState.Failed) else it
            },
        )
        applyToDevice(run, finishedAt, artifacts)
        val jobState = when (state) {
            BuildRunState.Succeeded -> JobState.Finished
            BuildRunState.Cancelled -> JobState.Cancelled
            else -> JobState.Failed
        }
        context.updateJob(
            journal(run, jobState).copy(
                finishedAtEpochMillis = finishedAt,
                summary = if (succeeded) artifacts.joinToString(", ") { it.fileName } else message,
            ),
        )
        publish(run)
    }

    private fun applyToDevice(
        run: MockBuildRun,
        finishedAt: Long,
        artifacts: List<ArtifactInfo>,
    ) {
        val device = context.state.value.device(run.snapshot.device) ?: return
        val updated = if (run.snapshot.state == BuildRunState.Succeeded) {
            device.copy(
                build = BuildStatus(BuildState.Built, run.snapshot.method, finishedAt),
                signed = SignedState.Signed,
                lastGoodBuild = BuildSummary(
                    buildId = run.snapshot.buildId,
                    method = run.snapshot.method,
                    mode = run.snapshot.mode,
                    image = run.snapshot.image,
                    finishedAtEpochMillis = finishedAt,
                    signed = true,
                ),
                artifacts = artifacts,
            )
        } else {
            device.copy(build = run.previousStatus, signed = run.previousSigned)
        }
        context.state.update { it.withDevice(updated) }
        context.deviceChanged(updated)
    }

    private fun publish(run: MockBuildRun) {
        run.events.tryEmit(BuildChanged(run.snapshot))
        context.emit(BuildChanged(run.snapshot))
    }
}

private fun initialSnapshot(
    buildId: String,
    device: MockDevice,
    method: BuildMethod,
    startedAtEpochMillis: Long,
) = BuildSnapshot(
    buildId = buildId,
    device = device.name,
    method = method,
    state = BuildRunState.Running,
    stages = BuildStage.entries.map { StageStatus(it, StageState.Pending) },
    startedAtEpochMillis = startedAtEpochMillis,
    image = if (method == BuildMethod.Remote) "bench-server:8443" else "ghcr.io/mcu-home/build-environment:0.1-r1",
    mode = if (method == BuildMethod.Remote) null else "container",
    parallelJobs = SAMPLE_PARALLEL_JOBS,
)

private fun journal(run: MockBuildRun, state: JobState): Job = Job(
    id = run.jobId,
    kind = JobKind.Build,
    device = run.snapshot.device,
    state = state,
    startedAtEpochMillis = run.snapshot.startedAtEpochMillis,
    method = run.snapshot.method,
    buildId = run.snapshot.buildId,
)
