// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildStage
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobKind
import org.mcuhome.ui.api.JobState
import org.mcuhome.ui.api.OutputLevel
import org.mcuhome.ui.api.Progress

/** How many compilation steps the simulated build reports. */
internal const val COMPILE_STEPS = 380

/** How many ticks the compile stage is delivered in. */
private const val COMPILE_TICKS = 20

/** One text line the simulated build prints. */
internal data class ScriptedLine(val text: String, val level: OutputLevel = OutputLevel.Plain)

/**
 * One step of a simulated build: how long it takes, how far it gets, and
 * what it prints while it does.
 *
 * The script is data rather than code so a build is a loop over a list —
 * which is what makes the stage order, the progress and the output the
 * same on every run and therefore assertable in a test.
 */
internal data class BuildTick(
    val stage: BuildStage,
    val durationMillis: Long,
    val progress: Progress? = null,
    val lines: List<ScriptedLine> = emptyList(),
    /** True on the tick that finishes the stage. */
    val completesStage: Boolean = false,
)

private val COMPILED_FILES = listOf(
    "src/main.c",
    "src/components/gpio_input.c",
    "src/components/gpio_output.c",
    "src/components/relay.c",
    "src/model/endpoints.c",
    "modules/matter/src/app/server/Server.cpp",
    "modules/matter/src/app/clusters/on-off-server/on-off-server.cpp",
    "modules/openthread/src/core/thread/mle.cpp",
    "modules/mcuboot/boot/bootutil/src/image_ecdsa.c",
    "zephyr/kernel/sched.c",
)

/**
 * The build of one device, tick by tick.
 *
 * Roughly four seconds at speed 1.0 — long enough that the stage row, the
 * progress bar and the scrolling output are all visibly doing something,
 * short enough that nobody waits for it.
 */
@Suppress("LongMethod")
internal fun buildScript(
    device: String,
    board: String,
    method: BuildMethod,
    signing: Boolean,
): List<BuildTick> {
    val ticks = mutableListOf<BuildTick>()
    ticks += BuildTick(
        stage = BuildStage.Generate,
        durationMillis = 400,
        lines = listOf(
            ScriptedLine("-- Resolving $device against the device model"),
            ScriptedLine("-- Generating the Zephyr application into build/$device/app"),
            ScriptedLine("-- Wrote 23 files"),
        ),
        completesStage = true,
    )
    ticks += BuildTick(
        stage = BuildStage.Configure,
        durationMillis = 900,
        lines = listOf(
            ScriptedLine("-- Configuring the application for $board"),
            ScriptedLine("-- Cache value CONFIG_MCUBOOT_SIGNATURE_TYPE_ECDSA_P256=y"),
            ScriptedLine("-- Configuring done"),
            ScriptedLine("-- Generating done"),
        ),
        completesStage = true,
    )
    val perTick = COMPILE_STEPS / COMPILE_TICKS
    for (tick in 1..COMPILE_TICKS) {
        val done = tick * perTick
        val file = COMPILED_FILES[(tick - 1) % COMPILED_FILES.size]
        val step = "[$done/$COMPILE_STEPS]"
        val lines = mutableListOf(ScriptedLine("$step Building C object app/CMakeFiles/app.dir/$file.obj"))
        if (tick == COMPILE_TICKS / 2) {
            lines += ScriptedLine(
                "warning: src/components/relay.c:41: unused variable 'retries' [-Wunused-variable]",
                OutputLevel.Warning,
            )
        }
        ticks += BuildTick(
            stage = BuildStage.Compile,
            durationMillis = 100,
            progress = Progress(done, COMPILE_STEPS),
            lines = lines,
            completesStage = tick == COMPILE_TICKS,
        )
    }
    ticks += BuildTick(
        stage = BuildStage.Link,
        durationMillis = 500,
        lines = listOf(
            ScriptedLine("[$COMPILE_STEPS/$COMPILE_STEPS] Linking C executable zephyr/zephyr.elf"),
            ScriptedLine("Memory region       Used Size  Region Size  %age Used"),
            ScriptedLine("           FLASH:     412360 B     1048576 B     39.33%"),
            ScriptedLine("             RAM:      98304 B      262144 B     37.50%"),
        ),
        completesStage = true,
    )
    ticks += if (signing) {
        BuildTick(
            stage = BuildStage.Sign,
            durationMillis = 400,
            lines = listOf(
                ScriptedLine("-- Signing zephyr.bin with secrets/firmware/signing.pem"),
                ScriptedLine("-- Wrote firmware.signed.bin"),
                ScriptedLine("Build finished: ${method.name.lowercase()} · $device"),
            ),
            completesStage = true,
        )
    } else {
        BuildTick(
            stage = BuildStage.Sign,
            durationMillis = 0,
            lines = listOf(ScriptedLine("-- Signing skipped; the image stays unsigned")),
            completesStage = true,
        )
    }
    return ticks
}

/** The three files a finished build leaves behind. */
internal fun sampleArtifacts(device: String): List<ArtifactInfo> = listOf(
    ArtifactInfo(
        path = "firmware.signed.bin",
        role = "firmware",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000001",
        sizeBytes = 421_888,
        root = "out",
    ),
    ArtifactInfo(
        path = "$device.ota",
        role = "ota-image",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000002",
        sizeBytes = 423_936,
        root = "out",
    ),
    ArtifactInfo(
        path = "build-report.json",
        role = "report",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000003",
        sizeBytes = 4_096,
        root = "out",
    ),
)

/**
 * The finished jobs the popover starts out with — the history the design
 * shows. The running entry is not here: it is created by the build the
 * mock starts for itself.
 *
 * Their identifiers say "past" so they cannot collide with the ones the
 * mock hands out from its counter while it runs.
 */
internal fun sampleJobs(): List<Job> = listOf(
    Job(
        id = "j-past-1",
        kind = JobKind.Build,
        device = "kitchen-sensor",
        state = JobState.Finished,
        startedAtEpochMillis = SAMPLE_TODAY_1241 - 252_000,
        finishedAtEpochMillis = SAMPLE_TODAY_1241,
        method = BuildMethod.Local,
        summary = "firmware.signed.bin, kitchen-sensor.ota",
        buildId = "b-kitchen-sensor-1",
    ),
    Job(
        id = "j-past-2",
        kind = JobKind.Validate,
        device = "hallway-light",
        state = JobState.Failed,
        startedAtEpochMillis = SAMPLE_TODAY_1238,
        finishedAtEpochMillis = SAMPLE_TODAY_1238,
        summary = "2 errors in main.yaml",
    ),
    Job(
        id = "j-past-3",
        kind = JobKind.Flash,
        device = "bench-node",
        state = JobState.Finished,
        startedAtEpochMillis = SAMPLE_YESTERDAY_1812 - 30_000,
        finishedAtEpochMillis = SAMPLE_YESTERDAY_1812,
        summary = "recovery · /dev/ttyACM1",
    ),
)
