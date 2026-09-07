// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.BoardInfo
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.NewDeviceRequest
import org.mcuhome.ui.api.Starter
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.MCUHomeTextField
import org.mcuhome.ui.component.ModalCard
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.SegmentedControl
import org.mcuhome.ui.theme.MCUHomeTheme

private val DialogWidth = 560.dp

/** The starters the dialog offers, in the order the design puts them. */
internal enum class StarterChoice(val label: String) {
    Minimal("Minimal"),
    SensorNode("Sensor node"),
    Light("Light"),
    CopyOf("Copy of existing…"),
}

/**
 * Creating a device: the two names, the board, and what its first
 * configuration starts from.
 *
 * The dialog talks to the API itself and shows what comes back where it
 * belongs — a refused name under the name field, anything else above the
 * buttons — instead of handing the failure to the screen behind it. The
 * name is checked here as well as on the server so that the answer to a
 * typo is immediate.
 */
@Composable
fun NewDeviceDialog(
    devices: List<DeviceSummary>,
    onDismiss: () -> Unit,
    onCreated: (DeviceSummary) -> Unit,
) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    val colors = MCUHomeTheme.colors
    val boards by produceState(emptyList<BoardInfo>(), api) { value = api.device.boards().boards }

    val nameState = rememberTextFieldState()
    val friendlyState = rememberTextFieldState()
    val boardQuery = rememberTextFieldState()
    var board by remember { mutableStateOf<String?>(null) }
    var starter by remember { mutableStateOf(StarterChoice.Minimal) }
    var copyOf by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocus.requestFocus() }

    val name = nameState.text.toString()
    val nameInvalid = name.isNotEmpty() && !isValidDeviceName(name)
    val request = newDeviceRequest(
        name = name,
        board = board,
        friendlyName = friendlyState.text.toString(),
        starter = starter,
        copyOf = copyOf,
    )

    fun create() {
        val pending = request ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onCreated(api.device.new(pending))
            } catch (failure: ApiException) {
                error = failure.error
            } finally {
                busy = false
            }
        }
    }

    ModalCard(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(DialogWidth),
        onSubmit = { create() },
    ) {
        Column(Modifier.padding(all = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "New device",
                    color = colors.ink,
                    fontFamily = MCUHomeTheme.typography.heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                Box(Modifier.weight(1f))
                MCUHomeIconButton(
                    icon = MCUHomeIcons.close,
                    contentDescription = "Close this dialog",
                    onClick = onDismiss,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabelledField(
                    label = "Device name",
                    modifier = Modifier.weight(1f),
                    hint = nameHint(name, nameInvalid),
                    hintIsError = nameInvalid,
                ) {
                    MCUHomeTextField(
                        state = nameState,
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                        placeholder = "porch-light",
                        mono = true,
                        invalid = nameInvalid,
                    )
                }
                LabelledField(
                    label = "Friendly name",
                    modifier = Modifier.weight(1f),
                    hint = AnnotatedString("Shown in Home Assistant and the device list."),
                ) {
                    MCUHomeTextField(
                        state = friendlyState,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = friendlyNameFor(name).ifEmpty { "Porch Light" },
                    )
                }
            }

            LabelledField(label = "Board", modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MCUHomeTextField(
                        state = boardQuery,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Search boards…",
                        leadingIcon = MCUHomeIcons.search,
                    )
                    BoardList(
                        boards = filterBoards(boards, boardQuery.text.toString()),
                        selected = board,
                        onSelect = { board = it.target },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            LabelledField(
                label = "Start from",
                modifier = Modifier.fillMaxWidth(),
                hint = starterHint(name),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentedControl(
                        options = StarterChoice.entries,
                        selected = starter,
                        onSelect = { starter = it },
                        label = { it.label },
                    )
                    if (starter == StarterChoice.CopyOf) {
                        SegmentedControl(
                            options = devices.map { it.name },
                            selected = copyOf.orEmpty(),
                            onSelect = { copyOf = it },
                            label = { it },
                        )
                    }
                }
            }

            error?.let { ErrorNotice(it, Modifier.fillMaxWidth()) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                SecondaryButton(text = "Cancel", onClick = onDismiss)
                PrimaryButton(
                    text = "Create device",
                    onClick = { create() },
                    icon = MCUHomeIcons.plus,
                    enabled = request != null && !busy,
                )
            }
        }
    }
}

/** A field with its heading above it and, where there is one, its explanation below. */
@Composable
private fun LabelledField(
    label: String,
    modifier: Modifier = Modifier,
    hint: AnnotatedString? = null,
    hintIsError: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Column(modifier) {
        Text(
            text = label,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
        if (hint != null) {
            Text(
                text = hint,
                color = if (hintIsError) colors.error else colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun nameHint(name: String, invalid: Boolean): AnnotatedString {
    val mono = SpanStyle(fontFamily = MCUHomeTheme.typography.mono)
    if (invalid) {
        return AnnotatedString("Not a usable name. $DEVICE_NAME_RULE It has to start with a letter.")
    }
    return buildAnnotatedString {
        append("Folder ")
        withStyle(mono) { append("devices/${name.ifEmpty { "<name>" }}/") }
        append(" and hostname. $DEVICE_NAME_RULE")
    }
}

@Composable
private fun starterHint(name: String): AnnotatedString {
    val mono = SpanStyle(fontFamily = MCUHomeTheme.typography.mono)
    return buildAnnotatedString {
        append("Writes a starter ")
        withStyle(mono) { append("main.yaml") }
        append(" and draws Matter pairing credentials into ")
        withStyle(mono) { append("secrets/devices/${name.ifEmpty { "<name>" }}.yaml") }
        append(".")
    }
}

/**
 * What the dialog has collected, or null while it is not enough to create
 * a device with.
 *
 * Keeping this a plain function of the fields is what lets the button's
 * enabled state and the request that is sent be the same decision: there
 * is no second place where "is this ready" could disagree with what is
 * actually sent.
 */
internal fun newDeviceRequest(
    name: String,
    board: String?,
    friendlyName: String,
    starter: StarterChoice,
    copyOf: String?,
): NewDeviceRequest? {
    if (!isValidDeviceName(name) || board == null) return null
    if (starter == StarterChoice.CopyOf && copyOf == null) return null
    return NewDeviceRequest(
        name = name,
        board = board,
        friendlyName = friendlyName.ifBlank { null },
        starter = starterOf(starter, copyOf),
    )
}

private fun starterOf(choice: StarterChoice, copyOf: String?): Starter = when (choice) {
    StarterChoice.Minimal -> Starter.Minimal
    StarterChoice.SensorNode -> Starter.SensorNode
    StarterChoice.Light -> Starter.Light
    StarterChoice.CopyOf -> copyOf?.let { Starter.CopyOf(it) } ?: Starter.Minimal
}
