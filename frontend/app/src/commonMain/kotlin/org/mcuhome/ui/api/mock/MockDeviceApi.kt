// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.flow.update
import org.mcuhome.ui.api.BoardRegistry
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.BuildStatus
import org.mcuhome.ui.api.DeviceAdded
import org.mcuhome.ui.api.DeviceApi
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.DeviceRemoved
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobKind
import org.mcuhome.ui.api.JobState
import org.mcuhome.ui.api.NetworkInfo
import org.mcuhome.ui.api.NewDeviceRequest
import org.mcuhome.ui.api.PairingSummary
import org.mcuhome.ui.api.ResolvedModel
import org.mcuhome.ui.api.SaveResult
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.api.Starter
import org.mcuhome.ui.api.ValidationReport

private val DEVICE_NAME = Regex("^[a-z][a-z0-9-]*$")

/** The devices of the in-memory project. */
internal class MockDeviceApi(private val context: MockContext) : DeviceApi {
    override suspend fun list(): List<DeviceSummary> {
        val state = context.state.value
        return state.devices.map { state.summary(it, context.now()) }
    }

    override suspend fun get(name: String): DeviceDetail {
        val state = context.state.value
        val device = context.requireDevice(name)
        val report = state.validate(device, device.text, context.now())
        return DeviceDetail(
            summary = state.summary(device, context.now()),
            path = device.path,
            yaml = device.text,
            revision = device.revision,
            includes = includedFiles(device.text),
            resolvedSecretCount = referencedSecrets(device.text).size,
            diagnostics = report.diagnostics,
            lastGoodBuild = device.lastGoodBuild,
            artifacts = device.artifacts,
            pairing = device.pairing?.let {
                PairingSummary(present = true, maskedDiscriminator = mask(it.discriminator.toString()))
            } ?: PairingSummary(present = false, maskedDiscriminator = ""),
        )
    }

    override suspend fun save(
        name: String,
        text: String,
        baseRevision: String,
    ): SaveResult {
        val device = context.requireDevice(name)
        if (device.revision != baseRevision) {
            return SaveResult.Conflict(device.revision, device.text)
        }
        val (revision, next) = context.state.value.nextRevision()
        val updated = device.copy(text = text, revision = revision)
        context.state.value = next.withDevice(updated)
        context.deviceChanged(updated)
        return SaveResult.Saved(revision)
    }

    override suspend fun validate(name: String, text: String?): ValidationReport {
        val state = context.state.value
        val device = context.requireDevice(name)
        val report = state.validate(device, text ?: device.text, context.now())
        if (text == null) context.addJob(validateJournal(context, device.name, report))
        return report
    }

    override suspend fun new(request: NewDeviceRequest): DeviceSummary {
        checkRequest(context.state.value, request)
        val (revision, next) = context.state.value.nextRevision()
        val device = MockDevice(
            name = request.name,
            friendlyName = request.friendlyName?.takeIf { it.isNotBlank() } ?: titleCase(request.name),
            board = request.board,
            network = networkOf(request.board),
            text = starterText(request, context.state.value),
            revision = revision,
            build = BuildStatus(BuildState.NeverBuilt),
            signed = SignedState.Unknown,
        )
        context.state.value = next.copy(devices = next.devices + device)
        val summary = context.state.value.summary(device, context.now())
        context.emit(DeviceAdded(summary))
        return summary
    }

    override suspend fun rename(name: String, newName: String): DeviceSummary {
        val device = context.requireDevice(name)
        if (!DEVICE_NAME.matches(newName)) throw invalidName(newName)
        if (context.state.value.device(newName) != null) {
            throw refused("There is already a device called \"$newName\".")
        }
        val renamed = device.copy(name = newName, text = device.text.replace("\n  name: $name", "\n  name: $newName"))
        context.state.update { state ->
            state.copy(devices = state.devices.map { if (it.name == name) renamed else it })
        }
        context.emit(DeviceRemoved(name))
        val summary = context.state.value.summary(renamed, context.now())
        context.emit(DeviceAdded(summary))
        return summary
    }

    override suspend fun delete(name: String) {
        context.requireDevice(name)
        context.state.update { state -> state.copy(devices = state.devices.filterNot { it.name == name }) }
        context.emit(DeviceRemoved(name))
    }

    override suspend fun clean(name: String?) {
        val targets = if (name == null) context.state.value.devices else listOf(context.requireDevice(name))
        for (device in targets) {
            val cleaned = device.copy(
                build = BuildStatus(BuildState.NeverBuilt),
                signed = SignedState.Unknown,
                lastGoodBuild = null,
                artifacts = emptyList(),
            )
            context.state.update { it.withDevice(cleaned) }
            context.deviceChanged(cleaned)
        }
    }

    override suspend fun boards(): BoardRegistry = SAMPLE_BOARDS

    override suspend fun model(name: String): ResolvedModel {
        val state = context.state.value
        val device = context.requireDevice(name)
        val report = state.validate(device, device.text, context.now())
        if (!report.ok) {
            throw refused(
                "${device.name} does not resolve to a model yet: ${report.errorCount} error(s) in its configuration.",
                hint = "fix the configuration; the Diagnostics tab lists what is wrong",
            )
        }
        return ResolvedModel(device = device.name, modelVersion = "1", json = modelJson(device))
    }
}

private fun checkRequest(state: MockState, request: NewDeviceRequest) {
    if (!DEVICE_NAME.matches(request.name)) throw invalidName(request.name)
    if (state.device(request.name) != null) throw refused("There is already a device called \"${request.name}\".")
    checkBoard(request.board)
}

private fun checkBoard(board: String) {
    if (board in SAMPLE_SUPPORTED_BOARDS) return
    val planned = SAMPLE_PLANNED_BOARDS[board]
    throw invalid(
        if (planned != null) {
            "MCUHome does not support the board \"$board\" yet: $planned."
        } else {
            "\"$board\" is not a board MCUHome knows."
        },
        hint = "pick one of: ${SAMPLE_SUPPORTED_BOARDS.sorted().joinToString(", ")}",
    )
}

private fun invalidName(name: String) = invalid(
    "\"$name\" is not a usable device name.",
    hint = "lowercase letters, digits and hyphens, starting with a letter — it becomes the folder and the hostname",
)

private fun titleCase(name: String): String =
    name.split('-').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

private fun networkOf(board: String): NetworkInfo {
    val info = SAMPLE_BOARDS.boards.first { it.target == board }
    return NetworkInfo(info.transports.first())
}

private fun validateJournal(
    context: MockContext,
    device: String,
    report: ValidationReport,
): Job {
    val errors = report.diagnostics.count { it.severity == DiagnosticSeverity.Error }
    return Job(
        id = context.nextId("j"),
        kind = JobKind.Validate,
        device = device,
        state = if (report.ok) JobState.Finished else JobState.Failed,
        startedAtEpochMillis = report.checkedAtEpochMillis,
        finishedAtEpochMillis = report.checkedAtEpochMillis,
        summary = if (report.ok) "valid" else "$errors error(s) in main.yaml",
    )
}

private fun modelJson(device: MockDevice): String = """
    {
      "model_version": "1",
      "device": {
        "name": "${device.name}",
        "friendly_name": "${device.friendlyName}",
        "board": "${device.board}"
      },
      "note": "The mock does not resolve a real model; the real one comes from the builder."
    }
""".trimIndent()

/**
 * The text a new device starts from.
 *
 * The four choices of the New device dialog: an empty file with the
 * commented example, two shapes that are worth a starting point of their
 * own, and a copy of a device that already exists.
 */
private fun starterText(request: NewDeviceRequest, state: MockState): String {
    val friendly = request.friendlyName?.takeIf { it.isNotBlank() } ?: titleCase(request.name)
    val starter = request.starter
    if (starter is Starter.CopyOf) {
        val source = state.device(starter.device)
            ?: throw notFound("There is no device called \"${starter.device}\" to copy.")
        return source.text
            .replace("name: ${source.name}", "name: ${request.name}")
            .replace("friendly_name: ${source.friendlyName}", "friendly_name: $friendly")
            .replace("board: ${source.board}", "board: ${request.board}")
    }
    val head = """
        device:
          board: ${request.board}
          name: ${request.name}
          friendly_name: $friendly
          power_source: mains

        packages:
          matter_defaults: !include ../../configs/matter-defaults.yaml

        network:
          matter:
            enabled: true
    """.trimIndent()
    return head + starterBody(starter)
}

private fun starterBody(starter: Starter): String = when (starter) {
    Starter.SensorNode -> """


        hardware:
          buses:
            sensor_i2c:
              controller: arduino_i2c
          peripherals:
            climate:
              driver: sensirion_sht4x
              bus: sensor_i2c
              address: 0x44

        endpoints:
          temperature:
            device_type: temperature_sensor
            source: climate.temperature
    """.trimIndent()

    Starter.Light -> """


        hardware:
          peripherals:
            lamp:
              driver: gpio_output
              pin: 13
              active_high: true

        endpoints:
          light:
            device_type: on_off_light
            source: lamp.output
    """.trimIndent()

    else -> """


        # hardware:
        #   peripherals:
        #     lamp:
        #       driver: gpio_output
        #       pin: 13
    """.trimIndent()
}
