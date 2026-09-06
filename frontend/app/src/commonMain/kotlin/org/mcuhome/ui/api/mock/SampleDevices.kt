// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0
//
// The sample project's device files. Every value here is invented: the
// network names, the addresses and the credentials are placeholders that
// exist to make the screens look like a real project, and none of them
// belongs to anything real.

package org.mcuhome.ui.api.mock

import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.NetworkInfo
import org.mcuhome.ui.api.NetworkTransport
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.api.ThreadRole

/**
 * The instant the sample project is frozen at: 2026-09-07 12:52:06 UTC.
 *
 * Every other timestamp in the sample is stated relative to it, so the
 * device table reads "today 12:41" and "yesterday 18:07" the way the design
 * shows it, whenever the mock is actually run.
 */
const val SAMPLE_NOW_EPOCH_MILLIS: Long = 1_788_785_526_000

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** 2026-09-07 12:41:00 UTC — "today 12:41". */
internal const val SAMPLE_TODAY_1241: Long = SAMPLE_NOW_EPOCH_MILLIS - 11 * MINUTE

/** 2026-09-07 12:38:00 UTC — the failed validate in the jobs popover. */
internal const val SAMPLE_TODAY_1238: Long = SAMPLE_NOW_EPOCH_MILLIS - 14 * MINUTE

/** 2026-09-07 12:49:00 UTC — when a shared configuration last changed. */
internal const val SAMPLE_TODAY_1249: Long = SAMPLE_NOW_EPOCH_MILLIS - 3 * MINUTE

/** 2026-09-06 18:07:00 UTC — "yesterday 18:07". */
internal const val SAMPLE_YESTERDAY_1807: Long = SAMPLE_NOW_EPOCH_MILLIS - 18 * HOUR - 45 * MINUTE

/** 2026-09-06 18:12:00 UTC — the finished flash in the jobs popover. */
internal const val SAMPLE_YESTERDAY_1812: Long = SAMPLE_YESTERDAY_1807 + 5 * MINUTE

/** 2026-09-04 16:20:00 UTC — "3 days ago". */
internal const val SAMPLE_THREE_DAYS_AGO: Long = SAMPLE_NOW_EPOCH_MILLIS - 2 * DAY - 20 * HOUR - 32 * MINUTE

/**
 * One device as the sample project starts out.
 *
 * The configuration state is not stated: it is whatever
 * [validateSample] derives from [yaml], so editing a file in the
 * interface really does change the pill in the table.
 */
internal data class SampleDevice(
    val name: String,
    val friendlyName: String,
    val board: String,
    val network: NetworkInfo,
    val yaml: String,
    val builtAtEpochMillis: Long? = null,
    val buildMethod: BuildMethod? = null,
    val signed: SignedState = SignedState.Unknown,
    val hasPairing: Boolean = true,
    val discriminator: Int = 0,
    val passcode: Int = 0,
    val manualCode: String = "",
    val qrPayload: String = "",
)

private val THREAD_MTD = NetworkInfo(NetworkTransport.Thread, ThreadRole.Mtd)
private val THREAD_FTD = NetworkInfo(NetworkTransport.Thread, ThreadRole.Ftd)
private val WIFI = NetworkInfo(NetworkTransport.WiFi)

private val KITCHEN_SENSOR_YAML = """
    device:
      board: nrf7002dk/nrf5340/cpuapp
      name: kitchen-sensor
      friendly_name: Kitchen Sensor
      power_source: battery

    packages:
      thread_common: !include ../../configs/thread-mtd-common.yaml
      matter_defaults: !include ../../configs/matter-defaults.yaml

    network:
      matter:
        discriminator: !secret matter_discriminator
        passcode: !secret matter_passcode

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
      humidity:
        device_type: humidity_sensor
        source: climate.humidity

    build:
      ota_url: !secret ota_server_url
""".trimIndent()

private val HALLWAY_LIGHT_YAML = """
    device:
      board: nrf52840dk/nrf52840
      name: hallway-light
      friendly_name: Hallway Light
      power_source: mains

    packages:
      thread_common: !include ../../configs/thread-ftd-common.yaml
      matter_defaults: !include ../../configs/matter-defaults.yaml

    network:
      matter:
        discriminator: !secret matter_discriminator
        passcode: !secret matter_passcode

    hardware:
      peripherals:
        lamp:
          driver: gpio_output
          pin: 13
          active_high: true

    # Two problems on purpose: there is no `automations` section, and the
    # dimming default refers to a secret nobody set.
    automations:
      - trigger: on_boot
        action: restore_state

    endpoints:
      light:
        device_type: on_off_light
        source: lamp.output
        default_level: !secret hallway_dim_default

    build:
      ota_url: !secret ota_server_url
""".trimIndent()

/**
 * The file the editor spike opened, and the one the device screens show.
 *
 * The Matter credentials are referenced under their plain names: the file
 * they live in is the device's own `secrets/devices/garage-door.yaml`, so
 * there is nothing to disambiguate with a prefix.
 */
val GARAGE_DOOR_YAML: String = """
    device:
      board: esp32c6_devkitc/esp32c6/hpcore
      name: garage-door
      friendly_name: Garage Door
      power_source: mains

    # Shared settings for every Wi-Fi device in this project.
    packages:
      wifi_common: !include ../../configs/wifi-common.yaml
      matter_defaults: !include ../../configs/matter-defaults.yaml

    network:
      matter:
        discriminator: !secret matter_discriminator
        passcode: !secret matter_passcode

    hardware:
      peripherals:
        door_contact:
          driver: gpio_input
          pin: 4
          debounce_ms: 30
        relay:
          driver: gpio_output
          active_high: false

    endpoints:
      door:
        device_type: door_lock
        source: relay.output

    build:
      ota_url: !secret ota_server_url
""".trimIndent()

private val BENCH_NODE_YAML = """
    device:
      board: nrf7002dk/nrf5340/cpuapp
      name: bench-node
      friendly_name: Bench Node
      power_source: usb

    packages:
      thread_common: !include ../../configs/thread-mtd-common.yaml
      matter_defaults: !include ../../configs/matter-defaults.yaml

    network:
      matter:
        discriminator: !secret matter_discriminator
        passcode: !secret matter_passcode

    hardware:
      peripherals:
        status_led:
          driver: gpio_output
          pin: 6
          active_high: true

    endpoints:
      indicator:
        device_type: on_off_light
        source: status_led.output

    build:
      ota_url: !secret ota_server_url
""".trimIndent()

private val OFFICE_PLUG_YAML = """
    device:
      board: nrf52840dk/nrf52840
      name: office-plug
      friendly_name: Office Plug
      power_source: mains

    packages:
      thread_common: !include ../../configs/thread-ftd-common.yaml
      matter_defaults: !include ../../configs/matter-defaults.yaml

    network:
      matter:
        discriminator: !secret matter_discriminator
        passcode: !secret matter_passcode

    hardware:
      peripherals:
        relay:
          driver: gpio_output
          active_high: true
        button:
          driver: gpio_input
          pin: 11
          pull: up

    endpoints:
      outlet:
        device_type: on_off_plugin_unit
        source: relay.output

    build:
      ota_url: !secret ota_server_url
""".trimIndent()

private val BALCONY_CLIMATE_YAML = """
    device:
      board: nrf7002dk/nrf5340/cpuapp
      name: balcony-climate
      friendly_name: Balcony Climate
      power_source: battery

    # No Matter credentials yet — "Draw new credentials" writes them, and
    # the two `!secret` lines every other device has follow from that.
    packages:
      thread_common: !include ../../configs/thread-mtd-common.yaml
      matter_defaults: !include ../../configs/matter-defaults.yaml

    network:
      matter:
        enabled: true

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

    build:
      ota_url: !secret ota_server_url
""".trimIndent()

/**
 * The six devices of the sample project, in the order the design's table
 * shows them.
 */
internal val SAMPLE_DEVICES: List<SampleDevice> = listOf(
    SampleDevice(
        name = "kitchen-sensor",
        friendlyName = "Kitchen Sensor",
        board = "nrf7002dk/nrf5340/cpuapp",
        network = THREAD_MTD,
        yaml = KITCHEN_SENSOR_YAML,
        builtAtEpochMillis = SAMPLE_TODAY_1241,
        buildMethod = BuildMethod.Local,
        signed = SignedState.Signed,
        discriminator = 3840,
        passcode = 20202021,
        manualCode = "3497-011-2332",
        qrPayload = "MT:Y.K9042C00KA0648G00",
    ),
    SampleDevice(
        name = "hallway-light",
        friendlyName = "Hallway Light",
        board = "nrf52840dk/nrf52840",
        network = THREAD_FTD,
        yaml = HALLWAY_LIGHT_YAML,
        discriminator = 1201,
        passcode = 55512012,
        manualCode = "1551-201-2012",
        qrPayload = "MT:Y.K9042C00KA1201G00",
    ),
    SampleDevice(
        name = "garage-door",
        friendlyName = "Garage Door",
        board = "esp32c6_devkitc/esp32c6/hpcore",
        network = WIFI,
        yaml = GARAGE_DOOR_YAML,
        builtAtEpochMillis = SAMPLE_YESTERDAY_1807,
        buildMethod = BuildMethod.Local,
        // The last build was never signed, which the table draws as a dash
        // and the device rail spells out as "not yet".
        signed = SignedState.Unknown,
        discriminator = 2314,
        passcode = 84213497,
        manualCode = "3497-011-2332",
        qrPayload = "MT:Y.K9042C00KA0648G00",
    ),
    SampleDevice(
        name = "bench-node",
        friendlyName = "Bench Node",
        board = "nrf7002dk/nrf5340/cpuapp",
        network = THREAD_MTD,
        yaml = BENCH_NODE_YAML,
        builtAtEpochMillis = SAMPLE_YESTERDAY_1807,
        buildMethod = BuildMethod.Remote,
        signed = SignedState.Unsigned,
        discriminator = 1907,
        passcode = 31415926,
        manualCode = "0641-259-3170",
        qrPayload = "MT:Y.K9042C00KA1907G00",
    ),
    SampleDevice(
        name = "office-plug",
        friendlyName = "Office Plug",
        board = "nrf52840dk/nrf52840",
        network = THREAD_FTD,
        yaml = OFFICE_PLUG_YAML,
        builtAtEpochMillis = SAMPLE_THREE_DAYS_AGO,
        buildMethod = BuildMethod.Local,
        signed = SignedState.Signed,
        discriminator = 2882,
        passcode = 27182818,
        manualCode = "5271-828-2882",
        qrPayload = "MT:Y.K9042C00KA2882G00",
    ),
    SampleDevice(
        name = "balcony-climate",
        friendlyName = "Balcony Climate",
        board = "nrf7002dk/nrf5340/cpuapp",
        network = THREAD_MTD,
        yaml = BALCONY_CLIMATE_YAML,
        hasPairing = false,
    ),
)
