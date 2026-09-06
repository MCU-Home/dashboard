// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mcuhome.ui.api.ApiEvent
import org.mcuhome.ui.api.ArtifactDownload
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.Availability
import org.mcuhome.ui.api.BuildApi
import org.mcuhome.ui.api.BuildEvent
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildSnapshot
import org.mcuhome.ui.api.Capability
import org.mcuhome.ui.api.ConfigApi
import org.mcuhome.ui.api.ConnectionState
import org.mcuhome.ui.api.DeviceApi
import org.mcuhome.ui.api.DeviceLogApi
import org.mcuhome.ui.api.FlashApi
import org.mcuhome.ui.api.FlashOptions
import org.mcuhome.ui.api.FlashRequest
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobApi
import org.mcuhome.ui.api.JobsCleared
import org.mcuhome.ui.api.LogLine
import org.mcuhome.ui.api.McuHomeApi
import org.mcuhome.ui.api.PairingApi
import org.mcuhome.ui.api.ProjectApi
import org.mcuhome.ui.api.SecretApi
import org.mcuhome.ui.api.ServerApi
import org.mcuhome.ui.api.ServerInfo
import org.mcuhome.ui.api.SetupApi

/** How many events the mock buffers before it starts dropping the oldest. */
private const val EVENT_BUFFER = 256

/**
 * The API, in memory, with a sample project behind it.
 *
 * Everything the interface can ask for is answered here: six devices with
 * real configurations, five shared configurations, secrets in all four
 * scopes, an option registry with layer origins, a board list, and builds
 * that stream their stages and their output over a few seconds and can be
 * cancelled. Validation is computed from the file's text, so editing
 * changes the result.
 *
 * It is deterministic on purpose. [clock] is the only source of "now" and
 * stands still unless a test moves it; identifiers are handed out from a
 * counter; the build script is a fixed list of steps. [speed] divides every
 * simulated wait, so the same build that takes four seconds on a screen
 * takes none at all in a test.
 *
 * @param scope the scope simulated builds run in. A user interface passes
 *   the scope its screen lives in; a test passes its own, so the mock stops
 *   when the test does.
 * @param startingBuild whether to start a build for `garage-door` right
 *   away — the state the design's device table shows. Tests that assert on
 *   the initial state turn it off.
 */
class MockApi(
    scope: CoroutineScope,
    clock: MockClock = MockClock.fixed(SAMPLE_NOW_EPOCH_MILLIS),
    speed: Double = 1.0,
    startingBuild: Boolean = true,
) : McuHomeApi {
    private val eventSink = MutableSharedFlow<ApiEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val connectionState = MutableStateFlow(ConnectionState.Connected)
    private val context = MockContext(MutableStateFlow(sampleState()), eventSink, clock, speed)
    private val engine = MockBuildEngine(context, scope)

    override val connection: StateFlow<ConnectionState> = connectionState.asStateFlow()
    override val events: Flow<ApiEvent> = eventSink.asSharedFlow()

    override val server: ServerApi = MockServerApi()
    override val device: DeviceApi = MockDeviceApi(context)
    override val build: BuildApi = MockBuildApi(context, engine)
    override val job: JobApi = MockJobApi(context, engine)
    override val secret: SecretApi = MockSecretApi(context)
    override val config: ConfigApi = MockConfigApi(context)
    override val project: ProjectApi = MockProjectApi(context)
    override val pairing: PairingApi = MockPairingApi(context)
    override val flash: FlashApi = MockFlashApi()
    override val setup: SetupApi = MockSetupApi()
    override val log: DeviceLogApi = MockDeviceLogApi()

    init {
        if (startingBuild) engine.start("garage-door", BuildMethod.Local)
    }

    /** Move the connection indicator, for a screen or a test that wants to see it. */
    fun setConnection(state: ConnectionState) {
        connectionState.value = state
    }
}

private class MockServerApi : ServerApi {
    override suspend fun info(): ServerInfo = ServerInfo(
        serverVersion = "0.1",
        workbenchVersion = "0.1",
        projectName = SAMPLE_PROJECT_NAME,
        projectId = SAMPLE_PROJECT_ID,
        projectRoot = SAMPLE_PROJECT_ROOT,
        capabilities = listOf(
            Capability("build", available = true),
            Capability("sign", available = true),
            Capability("pairing", available = true),
            Capability("flash", available = false, reason = FLASH_REASON),
            Capability("first-time-setup", available = false, reason = SETUP_REASON),
            Capability("device-log", available = false, reason = LOG_REASON),
        ),
    )
}

private class MockBuildApi(private val context: MockContext, private val engine: MockBuildEngine) : BuildApi {
    override suspend fun start(device: String, method: BuildMethod): BuildSnapshot = engine.start(device, method)

    override suspend fun cancel(buildId: String) = engine.cancel(buildId)

    override suspend fun status(buildId: String): BuildSnapshot = engine.status(buildId)

    override fun stream(buildId: String): Flow<BuildEvent> = engine.stream(buildId)

    override suspend fun artifacts(buildId: String): List<ArtifactInfo> = engine.status(buildId).artifacts

    override suspend fun download(buildId: String, path: String): ArtifactDownload {
        val artifact = engine.status(buildId).artifacts.firstOrNull { it.path == path }
            ?: throw notFound("The build \"$buildId\" produced no file called \"$path\".")
        return ArtifactDownload(
            url = "/api/build/$buildId/artifact/$path",
            fileName = artifact.fileName,
            sizeBytes = artifact.sizeBytes,
        )
    }

    override suspend fun sign(device: String): BuildSnapshot {
        val known = context.requireDevice(device)
        val last = known.lastGoodBuild
            ?: throw refused(
                "$device has no finished build to sign.",
                hint = "build it first; signing works on the image a build produced",
            )
        if (last.signed) throw refused("The last build of $device is already signed.")
        return engine.start(device, last.method)
    }
}

private class MockJobApi(private val context: MockContext, private val engine: MockBuildEngine) : JobApi {
    override suspend fun list(): List<Job> = context.state.value.jobs

    override suspend fun cancel(jobId: String) {
        val job = context.state.value.jobs.firstOrNull { it.id == jobId }
            ?: throw notFound("There is no job with the id \"$jobId\".")
        val buildId = job.buildId
            ?: throw refused(
                "A ${job.kind.name.lowercase()} job cannot be cancelled.",
                hint = "only a running build can be stopped",
            )
        engine.cancel(buildId)
    }

    override suspend fun clearFinished(): List<String> {
        val finished = context.state.value.jobs.filter { it.state.isFinished }.map { it.id }
        context.state.update { state -> state.copy(jobs = state.jobs.filterNot { it.id in finished }) }
        context.emit(JobsCleared(finished))
        return finished
    }
}

private const val FLASH_REASON =
    "Flashing is not built yet: the workbench has no working flash command, so nothing can write an image to a board."

private const val SETUP_REASON =
    "First-time setup is not built yet: putting MCUHome's bootloader on a board needs vendor tooling the workbench " +
        "does not drive yet."

private const val LOG_REASON =
    "The device log is not built yet: nothing opens a serial connection to a running device."

private class MockFlashApi : FlashApi {
    override suspend fun options(device: String): Availability<FlashOptions> =
        Availability.NotAvailable(FLASH_REASON, hint = "build and sign work; flash the image with the command line")

    override suspend fun start(request: FlashRequest): Availability<String> =
        Availability.NotAvailable(FLASH_REASON, hint = "build and sign work; flash the image with the command line")
}

private class MockSetupApi : SetupApi {
    override suspend fun start(device: String): Availability<String> = Availability.NotAvailable(SETUP_REASON)
}

private class MockDeviceLogApi : DeviceLogApi {
    override suspend fun open(device: String): Availability<Flow<LogLine>> = Availability.NotAvailable(LOG_REASON)
}
