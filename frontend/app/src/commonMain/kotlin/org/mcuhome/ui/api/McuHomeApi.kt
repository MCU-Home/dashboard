// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the interface can ask of a project, in one place.
 *
 * The whole front end programs against this type and nothing else. Two
 * implementations exist: an in-memory mock that runs inside the browser
 * with sample data, and a client that speaks to the back end over a
 * WebSocket. Every screen is written once, and a screen can be developed,
 * previewed and tested without a server.
 *
 * The shape follows from that. A request that has an answer is a `suspend`
 * function returning the answer, so a screen writes ordinary sequential
 * code and cancellation comes from the coroutine it runs in. Anything that
 * arrives without being asked — a build's progress, another window saving
 * a file — is a [Flow]. Failure is an [ApiException] carrying an
 * [ApiError]; the two cases that are not failures have their own types
 * ([SaveResult.Conflict] and [Availability.NotAvailable]).
 *
 * ## Wire vocabulary
 *
 * The Kotlin names map one to one onto the API's commands, so this
 * interface is also the definition of the wire protocol. Commands are
 * `<area>/<verb>`, events are `<noun>_<past participle>`, and the few
 * routes that carry files rather than messages are `/api/<area>/...`.
 *
 * | Function | Command |
 * |---|---|
 * | [ServerApi.info] | `server/info` |
 * | [DeviceApi.list] | `device/list` |
 * | [DeviceApi.get] | `device/get` |
 * | [DeviceApi.save] | `device/save` |
 * | [DeviceApi.validate] | `device/validate` |
 * | [DeviceApi.new] | `device/new` |
 * | [DeviceApi.rename] | `device/rename` |
 * | [DeviceApi.delete] | `device/delete` |
 * | [DeviceApi.clean] | `device/clean` |
 * | [DeviceApi.boards] | `device/boards` |
 * | [DeviceApi.model] | `device/model` |
 * | [BuildApi.start] | `build/start` |
 * | [BuildApi.cancel] | `build/cancel` |
 * | [BuildApi.status] | `build/status` |
 * | [BuildApi.stream] | `build/subscribe` + `build_changed`, `build_output_appended` |
 * | [BuildApi.artifacts] | `build/artifacts` |
 * | [BuildApi.download] | `build/download` → `GET /api/build/{build}/artifact/{path}` |
 * | [BuildApi.sign] | `build/sign` |
 * | [JobApi.list] | `job/list` |
 * | [JobApi.cancel] | `build/cancel` (a build is the only cancellable job today) |
 * | [JobApi.clearFinished] | `job/clear-finished` |
 * | [SecretApi.scopes] | `secret/scopes` |
 * | [SecretApi.list] | `secret/list` |
 * | [SecretApi.reveal] | `secret/reveal` |
 * | [SecretApi.set] | `secret/set` |
 * | [SecretApi.delete] | `secret/delete` |
 * | [ConfigApi.list] | `config/list` |
 * | [ConfigApi.read] | `config/read` |
 * | [ConfigApi.write] | `config/write` |
 * | [ConfigApi.new] | `config/new` |
 * | [ConfigApi.validateUsers] | `config/validate-users` |
 * | [ProjectApi.options] | `project/options` |
 * | [ProjectApi.setOption] | `project/set-option` |
 * | [ProjectApi.unsetOption] | `project/unset-option` |
 * | [ProjectApi.read] | `project/read` |
 * | [ProjectApi.write] | `project/write` |
 * | [ProjectApi.doctor] | `project/doctor` |
 * | [ProjectApi.publicKey] | `project/public-key` |
 * | [PairingApi.get] | `device/matter-pairing` |
 * | [PairingApi.draw] | `device/matter-pairing-new` |
 * | [FlashApi.options] | `flash/options` |
 * | [FlashApi.start] | `flash/start` |
 * | [SetupApi.start] | `device/first-time-setup` |
 * | [DeviceLogApi.open] | `log/subscribe` + `log_appended` |
 *
 * Areas named after the object they act on, verbs after the action:
 * `device`, `build`, `job`, `secret`, `config` (the shared configuration
 * files under `configs/`), `project` (the project file and its options),
 * `flash`, `log`, `server`. Pairing has no area of its own — the
 * credentials belong to a device, and the command line spells the same
 * operation `mcuhome device matter-pairing`.
 */
interface McuHomeApi {
    /** Whether there is a live connection, for the indicator in the top bar. */
    val connection: StateFlow<ConnectionState>

    /**
     * Everything the server reports without being asked, in arrival order.
     *
     * One stream rather than one per topic: a client subscribes to the
     * topics it needs and decodes them through a single decoder, and a
     * screen filters with [kotlinx.coroutines.flow.filterIsInstance].
     */
    val events: Flow<ApiEvent>

    val server: ServerApi
    val device: DeviceApi
    val build: BuildApi
    val job: JobApi
    val secret: SecretApi
    val config: ConfigApi
    val project: ProjectApi
    val pairing: PairingApi
    val flash: FlashApi
    val setup: SetupApi
    val log: DeviceLogApi
}

/** Who is on the other end. */
interface ServerApi {
    suspend fun info(): ServerInfo
}

/** The project's devices: the table, the editor, and everything around them. */
interface DeviceApi {
    /** Every device with its three state columns. */
    suspend fun list(): List<DeviceSummary>

    /** One device: its row, its file, and the state its rail shows. */
    suspend fun get(name: String): DeviceDetail

    /**
     * Write the device's `main.yaml`.
     *
     * [baseRevision] is the revision the editor started from. A write
     * against a stale revision is not an error — it answers
     * [SaveResult.Conflict] with the file as it stands now, and the
     * interface asks the user what to do.
     */
    suspend fun save(
        name: String,
        text: String,
        baseRevision: String,
    ): SaveResult

    /**
     * Check a configuration and report every problem at once.
     *
     * [text] is the editor's unsaved content: validating what is on screen
     * rather than what is on disk is what makes the gutter markers follow
     * the typing. Left null, the file on disk is checked.
     */
    suspend fun validate(name: String, text: String? = null): ValidationReport

    /** Create a device folder with a starter configuration. */
    suspend fun new(request: NewDeviceRequest): DeviceSummary

    suspend fun rename(name: String, newName: String): DeviceSummary

    suspend fun delete(name: String)

    /** Remove a device's build output. Null cleans every device. */
    suspend fun clean(name: String? = null)

    /** What MCUHome can build for, planned boards included. */
    suspend fun boards(): BoardRegistry

    /** The canonical model the configuration resolves to. */
    suspend fun model(name: String): ResolvedModel
}

/** Building firmware, and what a build leaves behind. */
interface BuildApi {
    /** Start a build and return its first snapshot. Fails if one is running. */
    suspend fun start(device: String, method: BuildMethod): BuildSnapshot

    suspend fun cancel(buildId: String)

    suspend fun status(buildId: String): BuildSnapshot

    /**
     * The build as it happens: stage changes and output lines, in order.
     *
     * The stream replays what the build has produced so far before it
     * follows along, so opening the Build tab halfway through a build shows
     * the whole run rather than the tail of it. It completes when the build
     * does.
     */
    fun stream(buildId: String): Flow<BuildEvent>

    /** The files of a build; for a device, of its last good build. */
    suspend fun artifacts(buildId: String): List<ArtifactInfo>

    /** Where to fetch one artifact's bytes. */
    suspend fun download(buildId: String, path: String): ArtifactDownload

    /** Sign the image of a finished build — the build's sign stage, alone. */
    suspend fun sign(device: String): BuildSnapshot
}

/** The jobs chip and its popover. */
interface JobApi {
    suspend fun list(): List<Job>

    /**
     * Stop a running job.
     *
     * Only a build can be stopped today; anything else refuses with
     * [ApiErrorCode.Refused], because the operations are short enough that
     * a cancel would arrive after they finished.
     */
    suspend fun cancel(jobId: String)

    /** Drop the finished entries from the popover; returns what was dropped. */
    suspend fun clearFinished(): List<String>
}

/**
 * The project's secrets.
 *
 * A list never carries a value. [reveal] is the only call that returns
 * secret material, it returns exactly one key, and it exists so that
 * showing a password is a deliberate act with a request behind it.
 */
interface SecretApi {
    /** The devices and build servers that have a secrets file of their own. */
    suspend fun scopes(): SecretScopeIndex

    suspend fun list(scope: SecretScope): SecretList

    suspend fun reveal(scope: SecretScope, key: String): String

    suspend fun set(
        scope: SecretScope,
        key: String,
        value: String,
    )

    suspend fun delete(scope: SecretScope, key: String)
}

/** The shared configuration files under `configs/`. */
interface ConfigApi {
    suspend fun list(): List<SharedConfigSummary>

    suspend fun read(fileName: String): SharedConfigFile

    /** Write a shared configuration; conflicts work as in [DeviceApi.save]. */
    suspend fun write(
        fileName: String,
        text: String,
        baseRevision: String,
    ): SaveResult

    suspend fun new(fileName: String): SharedConfigSummary

    /** Validate every device that includes this file. */
    suspend fun validateUsers(fileName: String): ConfigUsersReport
}

/** The project as a whole: its options, its file, its key, its health. */
interface ProjectApi {
    /** Every option with the value in force and the layer that set it. */
    suspend fun options(): List<ProjectOption>

    /** Write an option into the project layer. */
    suspend fun setOption(name: String, value: String): ProjectOption

    /** Remove an option from the project layer; the next layer takes over. */
    suspend fun unsetOption(name: String): ProjectOption

    /** The project file as text, for the "Edit as YAML" tab. */
    suspend fun read(): ProjectFile

    suspend fun write(text: String, baseRevision: String): SaveResult

    suspend fun doctor(): DoctorReport

    suspend fun publicKey(): PublicKey
}

/** A device's Matter commissioning credentials. */
interface PairingApi {
    suspend fun get(device: String): PairingCredentials

    /**
     * Draw fresh credentials.
     *
     * Refuses when credentials are already there unless [force] is set:
     * replacing them invalidates every code that was printed or handed out,
     * and the firmware has to be built and flashed again.
     */
    suspend fun draw(device: String, force: Boolean = false): PairingDrawResult
}

/**
 * Writing firmware onto a board.
 *
 * Declared, not available: the workbench has a stub for recovery flashing
 * and nothing for over-the-air updates. Both calls answer
 * [Availability.NotAvailable] with the reason until it does.
 */
interface FlashApi {
    suspend fun options(device: String): Availability<FlashOptions>

    /** Returns the id of the job that does the flashing. */
    suspend fun start(request: FlashRequest): Availability<String>
}

/**
 * One-time board provisioning: putting MCUHome's bootloader on a board
 * with the vendor's own tooling. Declared, not available yet.
 */
interface SetupApi {
    /** Returns the id of the job that does the provisioning. */
    suspend fun start(device: String): Availability<String>
}

/**
 * The live log of a running device.
 *
 * Declared, not available yet: nothing in the workbench opens a serial
 * monitor. The Flow the call returns ends when the log closes.
 */
interface DeviceLogApi {
    suspend fun open(device: String): Availability<Flow<LogLine>>
}
