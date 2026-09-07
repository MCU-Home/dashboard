// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.config

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
import org.mcuhome.ui.api.SharedConfigSummary
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeTextField
import org.mcuhome.ui.component.PromptActions
import org.mcuhome.ui.component.PromptCard
import org.mcuhome.ui.theme.MCUHomeTheme

/** What a configuration file may be called, in the words the server refuses it with. */
private const val CONFIG_NAME_RULE = "Lowercase letters, digits and hyphens, ending in .yaml."

private val CONFIG_FILE_NAME = Regex("^[a-z][a-z0-9-]*\\.yaml$")

/** Whether a name can be sent at all; the server checks the same thing again. */
fun isValidConfigFileName(name: String): Boolean = CONFIG_FILE_NAME.matches(name)

/**
 * Adding a file to `configs/`.
 *
 * The name is the whole question: a fragment starts as a comment saying
 * what it is for, and everything else is typed into the editor
 * afterwards.
 */
@Composable
fun NewConfigDialog(onDismiss: () -> Unit, onCreated: (SharedConfigSummary) -> Unit) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    val state = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    var error by remember { mutableStateOf<ApiError?>(null) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val name = state.text.toString()
    val invalid = name.isNotEmpty() && !isValidConfigFileName(name)

    fun create() {
        if (!isValidConfigFileName(name)) return
        error = null
        scope.launch {
            try {
                onCreated(api.config.new(name))
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    PromptCard(title = "New shared configuration", onDismiss = onDismiss, onSubmit = { create() }) {
        MCUHomeTextField(
            state = state,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = "thread-common.yaml",
            mono = true,
            invalid = invalid,
        )
        Text(
            text = "The file is created in configs/ and pulled in by a device with !include. $CONFIG_NAME_RULE",
            color = if (invalid) MCUHomeTheme.colors.error else MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.sp,
        )
        error?.let { ErrorNotice(it, Modifier.fillMaxWidth()) }
        PromptActions(
            confirm = "Create",
            enabled = isValidConfigFileName(name),
            onDismiss = onDismiss,
            onConfirm = { create() },
        )
    }
}
