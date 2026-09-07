// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.component.ControlHeight
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.MCUHomeTextField
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SegmentedControl
import org.mcuhome.ui.component.TouchControlHeight
import org.mcuhome.ui.shell.LocalWindowSize
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.rememberNowEpochMillis

/** Below this width the toolbar moves under the title instead of beside it. */
private val WIDE_LAYOUT = 1040.dp

private val FilterFieldWidth = 240.dp

/** The overlays the screen can have open; only ever one at a time. */
private sealed interface DevicesDialog {
    data object New : DevicesDialog

    data class Rename(val device: String) : DevicesDialog

    data class Delete(val device: String) : DevicesDialog
}

/**
 * The entry screen: every device of the project in one table, with the
 * filters that narrow it and the action that adds one.
 *
 * The table follows the server's events, so a build that another window
 * started, or the one running right now, moves here without the screen
 * asking again. Everything a row's menu starts is an ordinary API call;
 * what it refuses is shown above the table in the server's own words.
 */
@Composable
fun DevicesPage(onOpenDevice: (String) -> Unit, modifier: Modifier = Modifier) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    val devices by rememberDeviceList(api)
    val now by rememberNowEpochMillis()

    val filter = rememberTextFieldState()
    var mode by remember { mutableStateOf(DeviceFilterMode.All) }
    var sort by remember { mutableStateOf(DeviceSort()) }
    var dialog by remember { mutableStateOf<DevicesDialog?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }

    val query = filter.text.toString()
    val rows = remember(devices, query, mode, sort) { visibleDevices(devices, query, mode, sort) }

    fun run(block: suspend () -> Unit) {
        error = null
        scope.launch {
            try {
                block()
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    fun act(action: DeviceRowAction, device: DeviceSummary) {
        when (action) {
            DeviceRowAction.Open -> onOpenDevice(device.name)
            DeviceRowAction.Validate -> run { api.device.validate(device.name) }
            DeviceRowAction.Build -> run { api.build.start(device.name, BuildMethod.Local) }
            DeviceRowAction.Clean -> run { api.device.clean(device.name) }
            DeviceRowAction.Rename -> dialog = DevicesDialog.Rename(device.name)
            DeviceRowAction.Delete -> dialog = DevicesDialog.Delete(device.name)
        }
    }

    val window = LocalWindowSize.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (window.expanded) 32.dp else 16.dp,
                vertical = if (window.expanded) 24.dp else 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (window.compact) 12.dp else 16.dp),
    ) {
        DevicesHeader(
            count = rows.size,
            filter = filter,
            mode = mode,
            onMode = { mode = it },
            onNewDevice = { dialog = DevicesDialog.New },
        )
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth(), onDismiss = { error = null }) }
        if (window.compact) {
            DeviceRows(devices = rows, onOpenDevice = onOpenDevice, modifier = Modifier.fillMaxWidth())
        } else {
            DeviceTable(
                devices = rows,
                sort = sort,
                nowEpochMillis = now,
                onSort = { column -> sort = sort.clicked(column) },
                onRowAction = ::act,
                modifier = Modifier.fillMaxWidth(),
                columns = deviceColumns(window.sizeClass),
            )
        }
    }

    when (val open = dialog) {
        null -> Unit

        is DevicesDialog.New -> NewDeviceDialog(
            devices = devices,
            onDismiss = { dialog = null },
            onCreated = { created ->
                dialog = null
                onOpenDevice(created.name)
            },
        )

        is DevicesDialog.Rename -> RenameDeviceDialog(
            device = open.device,
            onDismiss = { dialog = null },
            onRenamed = { dialog = null },
        )

        is DevicesDialog.Delete -> DeleteDeviceDialog(
            device = open.device,
            onDismiss = { dialog = null },
            onDeleted = { dialog = null },
        )
    }
}

/**
 * The title, the count, and the three controls that narrow or extend the
 * table.
 *
 * The controls sit beside the title while there is room for them and move
 * onto their own line when there is not — one rule read from the width
 * that is actually available, rather than a layout per class of device.
 */
@Composable
private fun DevicesHeader(
    count: Int,
    filter: TextFieldState,
    mode: DeviceFilterMode,
    onMode: (DeviceFilterMode) -> Unit,
    onNewDevice: () -> Unit,
) {
    val window = LocalWindowSize.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (window.expanded && maxWidth >= WIDE_LAYOUT) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DevicesTitle(count)
                Box(Modifier.weight(1f))
                DevicesControls(filter, mode, onMode, onNewDevice)
            }
            return@BoxWithConstraints
        }
        // Below a desktop width the row breaks up rather than shrinks:
        // the action that adds a device stays beside the title where it
        // is expected, and the two controls that narrow the list take a
        // line each, in the order they are used.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DevicesTitle(count)
                Box(Modifier.weight(1f))
                NewDeviceButton(onNewDevice)
            }
            MCUHomeTextField(
                state = filter,
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Filter devices…",
                leadingIcon = MCUHomeIcons.search,
                height = controlHeight(window.compact),
            )
            SegmentedControl(
                options = DeviceFilterMode.entries,
                selected = mode,
                onSelect = onMode,
                label = { it.label },
                modifier = if (window.compact) Modifier.fillMaxWidth() else Modifier,
                fill = window.compact,
                segmentHeight = controlHeight(window.compact) - 4.dp,
            )
        }
    }
}

/** The height a control in the toolbar row is given: a finger's on a phone, a pointer's elsewhere. */
private fun controlHeight(compact: Boolean) = if (compact) TouchControlHeight else ControlHeight

@Composable
private fun NewDeviceButton(onNewDevice: () -> Unit) {
    val window = LocalWindowSize.current
    PrimaryButton(
        // A phone's button says the verb alone: what is being added is
        // written above it in letters twice the size.
        text = if (window.compact) "New" else "New device",
        onClick = onNewDevice,
        icon = MCUHomeIcons.plus,
        height = controlHeight(window.compact),
    )
}

@Composable
private fun DevicesTitle(count: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = "Devices",
            color = MCUHomeTheme.colors.ink,
            fontFamily = MCUHomeTheme.typography.heading,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
        )
        Text(
            text = count.toString(),
            color = MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
        )
    }
}

@Composable
private fun DevicesControls(
    filter: TextFieldState,
    mode: DeviceFilterMode,
    onMode: (DeviceFilterMode) -> Unit,
    onNewDevice: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MCUHomeTextField(
            state = filter,
            modifier = Modifier.width(FilterFieldWidth),
            placeholder = "Filter devices…",
            leadingIcon = MCUHomeIcons.search,
        )
        SegmentedControl(
            options = DeviceFilterMode.entries,
            selected = mode,
            onSelect = onMode,
            label = { it.label },
        )
        NewDeviceButton(onNewDevice)
    }
}
