// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.ResolvedModel
import org.mcuhome.ui.editor.EditorDiagnostic
import org.mcuhome.ui.editor.YamlEditor
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.editor.DiagnosticSeverity as EditorSeverity

private const val SPIKE_DEVICE = "garage-door"

/**
 * A page that exists to try the editor out in a real browser: one device's
 * file, its diagnostics in the gutter, and a dialog that has to appear
 * above the editor rather than behind it.
 *
 * It is the first screen that talks to the API: the file, the diagnostics
 * and the resolved model all come from it, and typing re-validates through
 * it, so the editor and the API are exercised together.
 *
 * It is reachable by typing its route and is not part of the navigation.
 * It goes away with the device screen that carries the finished editor.
 */
@Composable
fun SpikePage(modifier: Modifier = Modifier) {
    val colors = MCUHomeTheme.colors
    val api = LocalMcuHomeApi.current
    var detail by remember { mutableStateOf<DeviceDetail?>(null) }
    var diagnostics by remember { mutableStateOf(emptyList<EditorDiagnostic>()) }
    var model by remember { mutableStateOf<ResolvedModel?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }
    val state = remember { TextFieldState() }

    LaunchedEffect(api) {
        val loaded = api.device.get(SPIKE_DEVICE)
        detail = loaded
        state.setTextAndPlaceCursorAtEnd(loaded.yaml)
    }

    LaunchedEffect(api) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text.isEmpty()) return@collect
            diagnostics = api.device.validate(SPIKE_DEVICE, text).diagnostics.mapNotNull(::toEditorDiagnostic)
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Editor spike",
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.heading,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Text(
                text = detail?.path ?: "loading…",
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.mono,
                fontSize = 13.sp,
            )
            Button(onClick = { dialogOpen = true }) { Text("Show resolved model") }
        }

        YamlEditor(
            state = state,
            diagnostics = diagnostics,
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (dialogOpen) {
        LaunchedEffect(dialogOpen) {
            model = runCatching { api.device.model(SPIKE_DEVICE) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Resolved model") },
            text = {
                Text(
                    text = model?.json ?: "The configuration does not resolve to a model right now.",
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 12.sp,
                )
            },
            confirmButton = { TextButton(onClick = { dialogOpen = false }) { Text("Close") } },
        )
    }
}

/** An API diagnostic as the editor's gutter draws it; one without a line has nowhere to go. */
private fun toEditorDiagnostic(diagnostic: Diagnostic): EditorDiagnostic? {
    val line = diagnostic.line ?: return null
    return EditorDiagnostic(
        line = line,
        message = diagnostic.message,
        severity = when (diagnostic.severity) {
            DiagnosticSeverity.Error -> EditorSeverity.Error
            else -> EditorSeverity.Warning
        },
    )
}
