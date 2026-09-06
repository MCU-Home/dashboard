// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.BuildStatus
import org.mcuhome.ui.api.BuildSummary
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.ConfigStatus
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.NetworkInfo
import org.mcuhome.ui.api.PairingCredentials
import org.mcuhome.ui.api.ProjectOption
import org.mcuhome.ui.api.SecretScope
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.api.ValidationReport

/** One device of the in-memory project. */
internal data class MockDevice(
    val name: String,
    val friendlyName: String,
    val board: String,
    val network: NetworkInfo,
    val text: String,
    val revision: String,
    val build: BuildStatus,
    val signed: SignedState,
    val lastGoodBuild: BuildSummary? = null,
    val artifacts: List<ArtifactInfo> = emptyList(),
    val pairing: PairingCredentials? = null,
) {
    val path: String get() = "devices/$name/main.yaml"
}

/** One shared configuration file of the in-memory project. */
internal data class MockConfigFile(
    val fileName: String,
    val text: String,
    val revision: String,
    val changedAtEpochMillis: Long,
) {
    val path: String get() = "configs/$fileName"
}

/**
 * The whole in-memory project in one immutable value.
 *
 * Every command replaces this object rather than mutating parts of it, so
 * a reader always sees a consistent project and the mock needs no locking
 * of its own.
 */
internal data class MockState(
    val devices: List<MockDevice>,
    val configs: List<MockConfigFile>,
    val secrets: Map<SecretScope, Map<String, String>>,
    val options: List<ProjectOption>,
    val projectFileText: String,
    val projectFileRevision: String,
    val jobs: List<Job>,
    val revisionCounter: Int,
) {
    val configFileNames: Set<String> get() = configs.map { it.fileName }.toSet()

    fun device(name: String): MockDevice? = devices.firstOrNull { it.name == name }

    fun config(fileName: String): MockConfigFile? = configs.firstOrNull { it.fileName == fileName }

    /** The secrets a device's configuration may refer to: the project's, plus its own. */
    fun secretsVisibleTo(device: String): Set<String> =
        (secrets[SecretScope.Project].orEmpty().keys + secrets[SecretScope.Device(device)].orEmpty().keys)

    fun validate(
        device: MockDevice,
        text: String,
        nowEpochMillis: Long,
    ): ValidationReport = MockValidation.validate(
        file = device.path,
        text = text,
        knownSecrets = secretsVisibleTo(device.name),
        knownConfigFiles = configFileNames,
        checkedAtEpochMillis = nowEpochMillis,
    )

    fun summary(device: MockDevice, nowEpochMillis: Long): DeviceSummary {
        val report = validate(device, device.text, nowEpochMillis)
        return DeviceSummary(
            name = device.name,
            friendlyName = device.friendlyName,
            board = device.board,
            config = configStatus(report),
            build = device.build,
            signed = device.signed,
            network = device.network,
        )
    }

    fun withDevice(device: MockDevice): MockState =
        copy(devices = devices.map { if (it.name == device.name) device else it })

    fun withConfig(config: MockConfigFile): MockState =
        copy(configs = configs.map { if (it.fileName == config.fileName) config else it })

    fun withJob(job: Job): MockState =
        copy(jobs = if (jobs.any { it.id == job.id }) jobs.map { if (it.id == job.id) job else it } else jobs + job)

    /** The next revision string, and the state that has handed it out. */
    fun nextRevision(): Pair<String, MockState> =
        "r${revisionCounter + 1}" to copy(revisionCounter = revisionCounter + 1)
}

/** The Config pill: valid, or the counts behind it. */
internal fun configStatus(report: ValidationReport): ConfigStatus {
    val errors = report.diagnostics.count { it.severity == DiagnosticSeverity.Error }
    val warnings = report.diagnostics.count { it.severity == DiagnosticSeverity.Warning }
    val state = when {
        errors > 0 -> ConfigState.Errors
        warnings > 0 -> ConfigState.Warnings
        else -> ConfigState.Valid
    }
    return ConfigStatus(state, errorCount = errors, warningCount = warnings)
}

/** The sample project, as every screen and every test starts from it. */
internal fun sampleState(): MockState {
    val devices = SAMPLE_DEVICES.map { sample ->
        MockDevice(
            name = sample.name,
            friendlyName = sample.friendlyName,
            board = sample.board,
            network = sample.network,
            text = sample.yaml,
            revision = "r1",
            build = sampleBuildStatus(sample),
            signed = sample.signed,
            lastGoodBuild = sampleLastGoodBuild(sample),
            artifacts = if (sample.builtAtEpochMillis == null) emptyList() else sampleArtifacts(sample.name),
            pairing = samplePairing(sample),
        )
    }
    return MockState(
        devices = devices,
        configs = SAMPLE_CONFIGS.map {
            MockConfigFile(it.fileName, it.text, "r1", it.changedAtEpochMillis)
        },
        secrets = SAMPLE_SECRETS,
        options = SAMPLE_OPTIONS,
        projectFileText = SAMPLE_PROJECT_FILE_TEXT,
        projectFileRevision = "r1",
        jobs = sampleJobs(),
        revisionCounter = 1,
    )
}

private fun sampleBuildStatus(sample: SampleDevice): BuildStatus = BuildStatus(
    state = if (sample.builtAtEpochMillis == null) BuildState.NeverBuilt else BuildState.Built,
    method = sample.buildMethod,
    finishedAtEpochMillis = sample.builtAtEpochMillis,
)

private fun sampleLastGoodBuild(sample: SampleDevice): BuildSummary? {
    val finished = sample.builtAtEpochMillis ?: return null
    return BuildSummary(
        buildId = "b-${sample.name}-1",
        method = sample.buildMethod ?: BuildMethod.Local,
        mode = if (sample.buildMethod == BuildMethod.Remote) null else "container",
        image = if (sample.buildMethod == BuildMethod.Remote) {
            "bench-server:8443"
        } else {
            "ghcr.io/mcu-home/build-environment:0.1-r1"
        },
        finishedAtEpochMillis = finished,
        signed = sample.signed == SignedState.Signed,
    )
}

private fun samplePairing(sample: SampleDevice): PairingCredentials? {
    if (!sample.hasPairing) return null
    return PairingCredentials(
        device = sample.name,
        discriminator = sample.discriminator,
        passcode = sample.passcode,
        manualCode = sample.manualCode,
        qrPayload = sample.qrPayload,
        secretsFile = "secrets/devices/${sample.name}.yaml",
        testCredentials = false,
    )
}
