// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import org.mcuhome.ui.api.DeviceAdded
import org.mcuhome.ui.api.DeviceChanged
import org.mcuhome.ui.api.DeviceRemoved
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.EventsDropped
import org.mcuhome.ui.api.McuHomeApi

/**
 * The project's devices, kept current for as long as a screen shows them.
 *
 * The list is read once and then follows the events the server sends, so
 * a build that finishes or a device another window creates appears
 * without the screen asking again. When the server admits it dropped
 * events, the list is read again rather than patched — a gap in the
 * stream is exactly the case where a cache cannot be trusted.
 */
@Composable
fun rememberDeviceList(api: McuHomeApi): State<List<DeviceSummary>> = produceState(emptyList(), api) {
    value = api.device.list()
    api.events.collect { event ->
        value = when (event) {
            is DeviceAdded -> value.filterNot { it.name == event.summary.name } + event.summary
            is DeviceChanged -> value.map { if (it.name == event.summary.name) event.summary else it }
            is DeviceRemoved -> value.filterNot { it.name == event.device }
            is EventsDropped -> api.device.list()
            else -> value
        }
    }
}
