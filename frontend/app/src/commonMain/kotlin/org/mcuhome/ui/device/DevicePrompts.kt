// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeTextField
import org.mcuhome.ui.component.PromptActions
import org.mcuhome.ui.component.PromptCard
import org.mcuhome.ui.theme.MCUHomeTheme

/** Giving a device a new name: the same rule the New device dialog applies. */
@Composable
fun RenameDeviceDialog(
    device: String,
    onDismiss: () -> Unit,
    onRenamed: (DeviceSummary) -> Unit,
) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    val state = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    var error by remember { mutableStateOf<ApiError?>(null) }
    LaunchedEffect(device) {
        state.setTextAndPlaceCursorAtEnd(device)
        focus.requestFocus()
    }

    val newName = state.text.toString()
    val invalid = newName.isNotEmpty() && !isValidDeviceName(newName)
    val ready = isValidDeviceName(newName) && newName != device

    fun rename() {
        if (!ready) return
        error = null
        scope.launch {
            try {
                onRenamed(api.device.rename(device, newName))
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    PromptCard(title = "Rename $device", onDismiss = onDismiss, onSubmit = { rename() }) {
        MCUHomeTextField(
            state = state,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            mono = true,
            invalid = invalid,
        )
        Text(
            text = "The folder under devices/ and the host name change with it. $DEVICE_NAME_RULE",
            color = if (invalid) MCUHomeTheme.colors.error else MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.sp,
        )
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth()) }
        PromptActions(confirm = "Rename", enabled = ready, onDismiss = onDismiss, onConfirm = { rename() })
    }
}

/** Deleting a device, which takes its configuration and its build output with it. */
@Composable
fun DeleteDeviceDialog(
    device: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<ApiError?>(null) }

    fun delete() {
        error = null
        scope.launch {
            try {
                api.device.delete(device)
                onDeleted()
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    PromptCard(title = "Delete $device?", onDismiss = onDismiss, onSubmit = { delete() }) {
        Text(
            text = "This removes the device's folder, its configuration and everything built from it. " +
                "It cannot be undone from here.",
            color = MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
        )
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth()) }
        PromptActions(confirm = "Delete", enabled = true, onDismiss = onDismiss, onConfirm = { delete() })
    }
}
