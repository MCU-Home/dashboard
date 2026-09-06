// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0
//
// Everything the sample project holds besides its devices: the shared
// configuration files, the secrets, the option registry, the board list
// and the doctor report. Every value is invented — there is no real
// network name, no real address and no real key anywhere in this file.

package org.mcuhome.ui.api.mock

import org.mcuhome.ui.api.BoardInfo
import org.mcuhome.ui.api.BoardRegistry
import org.mcuhome.ui.api.DoctorCheck
import org.mcuhome.ui.api.DoctorSection
import org.mcuhome.ui.api.DoctorStatus
import org.mcuhome.ui.api.NetworkTransport
import org.mcuhome.ui.api.OptionKind
import org.mcuhome.ui.api.OptionOrigin
import org.mcuhome.ui.api.ProjectOption
import org.mcuhome.ui.api.PublicKey
import org.mcuhome.ui.api.SecretScope

/** The project the sample data describes. */
internal const val SAMPLE_PROJECT_NAME = "my-home"
internal const val SAMPLE_PROJECT_ID = "a3f9c2"
internal const val SAMPLE_PROJECT_ROOT = "/home/sample/projects/my-home"

/** One shared configuration file of the sample project. */
internal data class SampleConfig(val fileName: String, val text: String, val changedAtEpochMillis: Long)

private val WIFI_COMMON = """
    # Included by every Wi-Fi device in this project. Keys here are defaults;
    # a device overrides them by repeating the key in its own main.yaml.
    network:
      wifi:
        ssid: !secret wifi_ssid
        password: !secret wifi_password
        power_save: false
      matter:
        enabled: true

    build:
      kconfig:
        CONFIG_LOG_DEFAULT_LEVEL: 3
        CONFIG_NET_L2_WIFI_MGMT: y
""".trimIndent()

private val THREAD_MTD_COMMON = """
    # Included by every battery-powered Thread device: a minimal Thread
    # device that sleeps between polls and keeps no routing table.
    network:
      thread:
        network_key: !secret thread_network_key
        pskc: !secret thread_pskc
        device_type: mtd
        poll_period_ms: 2000
      matter:
        enabled: true

    build:
      kconfig:
        CONFIG_OPENTHREAD_MTD: y
        CONFIG_OPENTHREAD_MTD_SED: y
""".trimIndent()

private val THREAD_FTD_COMMON = """
    # Included by every mains-powered Thread device: a full Thread device
    # that stays awake and routes for its neighbours.
    network:
      thread:
        network_key: !secret thread_network_key
        pskc: !secret thread_pskc
        device_type: ftd
      matter:
        enabled: true

    build:
      kconfig:
        CONFIG_OPENTHREAD_FTD: y
""".trimIndent()

private val MATTER_DEFAULTS = """
    # Included by every device: the vendor identity and the commissioning
    # window this project shares. The identifiers are the ones Matter
    # reserves for testing.
    device:
      vendor_id: 0xfff1
      product_id: 0x8001

    network:
      matter:
        enabled: true
        commissioning_window_s: 900
""".trimIndent()

private val SENSOR_REPORTING = """
    # Reporting intervals for sensor endpoints. Nothing includes this file
    # yet; it is the starting point for the next batch of sensors.
    endpoints:
      defaults:
        min_interval_s: 30
        max_interval_s: 600
        change_threshold: 0.5
""".trimIndent()

/** The five files in the sample project's `configs/` directory. */
internal val SAMPLE_CONFIGS: List<SampleConfig> = listOf(
    SampleConfig("thread-mtd-common.yaml", THREAD_MTD_COMMON, SAMPLE_THREE_DAYS_AGO),
    SampleConfig("thread-ftd-common.yaml", THREAD_FTD_COMMON, SAMPLE_THREE_DAYS_AGO),
    SampleConfig("wifi-common.yaml", WIFI_COMMON, SAMPLE_TODAY_1249),
    SampleConfig("matter-defaults.yaml", MATTER_DEFAULTS, SAMPLE_YESTERDAY_1807),
    SampleConfig("sensor-reporting.yaml", SENSOR_REPORTING, SAMPLE_THREE_DAYS_AGO),
)

/**
 * The sample project's secret values, by scope.
 *
 * These are the values `secret/reveal` hands out one at a time. They are
 * placeholders: no network of ours is called this, and the two long hex
 * strings are counted digits, not key material.
 */
internal val SAMPLE_SECRETS: Map<SecretScope, Map<String, String>> = buildMap {
    put(
        SecretScope.Project,
        linkedMapOf(
            "wifi_ssid" to "mcuhome-demo",
            "wifi_password" to "example-password",
            "thread_network_key" to "00112233445566778899aabbccddeeff",
            "thread_pskc" to "0123456789abcdef0123456789abcdef",
            "ota_server_url" to "https://ota.example.invalid",
            "legacy_api_token" to "unused-1234",
        ),
    )
    for (device in SAMPLE_DEVICES) {
        if (!device.hasPairing) continue
        put(
            SecretScope.Device(device.name),
            linkedMapOf(
                "matter_discriminator" to device.discriminator.toString(),
                "matter_passcode" to device.passcode.toString(),
                "matter_salt" to "U1BBS0UyUCBTYW1wbGUgU2FsdA==",
                "matter_iterations" to "1000",
            ),
        )
    }
    put(
        SecretScope.BuildServer("workstation"),
        linkedMapOf("token" to "example-build-token"),
    )
    put(
        SecretScope.FirmwareKey,
        linkedMapOf(
            "signing.pem" to "(the private key; never leaves the project)",
            "signing.pub" to "(the public half; mcuhome public-key prints it)",
        ),
    )
}

/**
 * The project's options, as the Project screen shows them.
 *
 * The names are the builder's own configuration keys, spelled the way a
 * configuration file spells them — an interface that invented friendlier
 * names would be teaching a vocabulary the command line does not share.
 * The labels are what the table prints.
 */
internal val SAMPLE_OPTIONS: List<ProjectOption> = listOf(
    ProjectOption(
        name = "default_builder",
        label = "default_builder",
        help = "the builder a plain build uses",
        kind = OptionKind.Text,
        value = "workstation",
        origin = OptionOrigin.Project,
        choices = listOf("workstation", "bench-server"),
    ),
    ProjectOption(
        name = "build.mode",
        label = "build.mode",
        help = "how a local build is executed: in a build container, or as a child process",
        kind = OptionKind.Text,
        value = "container",
        origin = OptionOrigin.Project,
        defaultValue = "container",
        choices = listOf("container", "subprocess"),
    ),
    ProjectOption(
        name = "jobs",
        label = "jobs",
        help = "parallel compile jobs a build may use",
        kind = OptionKind.Integer,
        value = "8",
        origin = OptionOrigin.User,
        defaultValue = "1",
    ),
    ProjectOption(
        name = "build.sdk_sources",
        label = "build.sdk_sources",
        help = "directories holding hash-pinned MCUHome SDK packages",
        kind = OptionKind.Paths,
        value = "../mcuhome-sdk",
        origin = OptionOrigin.Project,
    ),
    ProjectOption(
        name = "signing_key",
        label = "signing_key",
        help = "a firmware signing key file to use instead of the project's",
        kind = OptionKind.Path,
        value = null,
        origin = OptionOrigin.Default,
    ),
    ProjectOption(
        name = "build.env_store",
        label = "build.env_store",
        help = "where unpacked build environments are kept; unset means the user cache directory",
        kind = OptionKind.Path,
        value = "/var/cache/mcuhome/build-environments",
        origin = OptionOrigin.System,
    ),
    ProjectOption(
        name = "build.python",
        label = "build.python",
        help = "the Python that creates a build environment's virtual environment",
        kind = OptionKind.Text,
        value = null,
        origin = OptionOrigin.Default,
    ),
    ProjectOption(
        name = "ccache_dir",
        label = "ccache_dir",
        help = "where the compiler cache lives; unset means the user cache directory",
        kind = OptionKind.Path,
        value = null,
        origin = OptionOrigin.Default,
    ),
)

/** The project file, as the "Edit as YAML" tab opens it. */
internal val SAMPLE_PROJECT_FILE_TEXT: String = """
    # The project layer of the configuration. Options set here apply to
    # every build of this project, whoever runs it.
    version: 1

    default_builder: workstation

    build:
      mode: container
      sdk_sources:
        - ../mcuhome-sdk
""".trimIndent()

/**
 * What MCUHome can build for.
 *
 * A planned board is listed with the reason it is not there yet rather
 * than hidden, so the New device dialog can say why instead of showing a
 * short list with no explanation.
 */
internal val SAMPLE_BOARDS = BoardRegistry(
    registryVersion = "1",
    boards = listOf(
        BoardInfo(
            target = "nrf52840dk/nrf52840",
            displayName = "Nordic nRF52840 DK",
            vendor = "Nordic Semiconductor",
            transports = listOf(NetworkTransport.Thread),
        ),
        BoardInfo(
            target = "nrf7002dk/nrf5340/cpuapp",
            displayName = "Nordic nRF7002 DK",
            vendor = "Nordic Semiconductor",
            transports = listOf(NetworkTransport.Thread, NetworkTransport.WiFi),
        ),
        BoardInfo(
            target = "nrf54l15dk/nrf54l15/cpuapp",
            displayName = "Nordic nRF54L15 DK",
            vendor = "Nordic Semiconductor",
            transports = listOf(NetworkTransport.Thread),
        ),
        BoardInfo(
            target = "esp32c6_devkitc/esp32c6/hpcore",
            displayName = "Espressif ESP32-C6-DevKitC",
            vendor = "Espressif",
            transports = listOf(NetworkTransport.Thread, NetworkTransport.WiFi),
        ),
        BoardInfo(
            target = "nrf5340dk/nrf5340/cpuapp",
            displayName = "Nordic nRF5340 DK",
            vendor = "Nordic Semiconductor",
            transports = listOf(NetworkTransport.Thread),
            planned = true,
            plannedReason = "not brought up yet",
        ),
    ),
)

/** The board targets a device configuration may name. */
internal val SAMPLE_SUPPORTED_BOARDS: Set<String> =
    SAMPLE_BOARDS.boards.filterNot { it.planned }.map { it.target }.toSet()

/** The planned board targets, with the reason each is not usable yet. */
internal val SAMPLE_PLANNED_BOARDS: Map<String, String> =
    SAMPLE_BOARDS.boards.filter { it.planned }.associate { it.target to (it.plannedReason ?: "") }

/**
 * The public half of the sample project's signing key.
 *
 * A placeholder, not a key: the base64 body spells out that it is one, so
 * nothing can mistake it for material worth using.
 */
internal val SAMPLE_PUBLIC_KEY = PublicKey(
    pem = """
        -----BEGIN PUBLIC KEY-----
        AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
        AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==
        -----END PUBLIC KEY-----
    """.trimIndent(),
    keyFile = "secrets/firmware/signing.pem",
    algorithm = "ECDSA P-256",
)

/** What the Doctor tab reports for the sample project. */
internal val SAMPLE_DOCTOR_SECTIONS: List<DoctorSection> = listOf(
    DoctorSection(
        title = "Project",
        checks = listOf(
            DoctorCheck("Project root", DoctorStatus.Ok, SAMPLE_PROJECT_ROOT),
            DoctorCheck("Layout version", DoctorStatus.Ok, "current"),
            DoctorCheck(
                "Secrets file permissions",
                DoctorStatus.Warning,
                "secrets/main.yaml is readable by other users.",
                hint = "chmod 600 secrets/main.yaml",
            ),
        ),
    ),
    DoctorSection(
        title = "Builders",
        checks = listOf(
            DoctorCheck("workstation", DoctorStatus.Ok, "local, container"),
            DoctorCheck(
                "bench-server",
                DoctorStatus.Warning,
                "No token configured for the remote builder.",
                hint = "add it under Secrets, scope Build server",
            ),
        ),
    ),
    DoctorSection(
        title = "Environment",
        checks = listOf(
            DoctorCheck("Container runtime", DoctorStatus.Ok, "available"),
            DoctorCheck("Signing key", DoctorStatus.Ok, "secrets/firmware/signing.pem"),
            DoctorCheck(
                "Serial access",
                DoctorStatus.Skipped,
                "Flashing is not available yet, so nothing was checked.",
            ),
        ),
    ),
)
