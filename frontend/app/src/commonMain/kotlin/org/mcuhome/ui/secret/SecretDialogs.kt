// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.secret

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.SecretScope
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeTextField
import org.mcuhome.ui.component.PromptActions
import org.mcuhome.ui.component.PromptCard
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * Writing a secret: a new key, or a new value for one that is there.
 *
 * `secret/set` writes both cases, so this is one dialog with the key
 * fixed in the second — a secret whose key changes is a different secret,
 * and renaming it would leave every `!secret` reference pointing at
 * nothing.
 */
@Composable
fun SecretDialog(
    scope: SecretScope,
    path: String,
    existingKey: String?,
    onDismiss: () -> Unit,
    onWritten: () -> Unit,
) {
    val api = LocalMcuHomeApi.current
    val coroutineScope = rememberCoroutineScope()
    val keyState = rememberTextFieldState()
    val valueState = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    var error by remember { mutableStateOf<ApiError?>(null) }
    LaunchedEffect(existingKey) { focus.requestFocus() }

    val key = existingKey ?: keyState.text.toString()
    val value = valueState.text.toString()
    val ready = key.isNotBlank() && value.isNotEmpty()

    fun write() {
        if (!ready) return
        error = null
        coroutineScope.launch {
            try {
                api.secret.set(scope, key, value)
                onWritten()
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    PromptCard(
        title = if (existingKey == null) "Add a secret" else "New value for $existingKey",
        onDismiss = onDismiss,
        onSubmit = { write() },
    ) {
        if (existingKey == null) {
            MCUHomeTextField(
                state = keyState,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = "wifi_password",
                mono = true,
            )
        }
        MCUHomeTextField(
            state = valueState,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (existingKey == null) Modifier else Modifier.focusRequester(focus)),
            placeholder = "the value",
            mono = true,
        )
        Text(
            text = "Written to $path. Any configuration reaches it with " +
                "!secret ${key.ifBlank { "<key>" }}.",
            color = MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.sp,
        )
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth()) }
        PromptActions(
            confirm = if (existingKey == null) "Add" else "Save",
            enabled = ready,
            onDismiss = onDismiss,
            onConfirm = { write() },
        )
    }
}

/** Taking a secret out of its file, with what still refers to it as the warning. */
@Composable
fun DeleteSecretDialog(
    scope: SecretScope,
    key: String,
    usedBy: String?,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val api = LocalMcuHomeApi.current
    val coroutineScope = rememberCoroutineScope()
    var error by remember { mutableStateOf<ApiError?>(null) }

    fun delete() {
        error = null
        coroutineScope.launch {
            try {
                api.secret.delete(scope, key)
                onDeleted()
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    PromptCard(title = "Delete $key?", onDismiss = onDismiss, onSubmit = { delete() }) {
        Text(
            text = if (usedBy == null) {
                "The key is removed from the secrets file. Nothing refers to it today."
            } else {
                "The key is removed from the secrets file. Every !secret $key that is left stops " +
                    "resolving, and what holds one fails to validate — today that is $usedBy."
            },
            color = MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
        )
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth()) }
        PromptActions(
            confirm = "Delete",
            enabled = true,
            onDismiss = onDismiss,
            onConfirm = { delete() },
            danger = true,
        )
    }
}
