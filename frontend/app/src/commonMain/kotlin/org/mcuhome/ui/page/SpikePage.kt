// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.editor.DiagnosticSeverity
import org.mcuhome.ui.editor.EditorDiagnostic
import org.mcuhome.ui.editor.YamlEditor
import org.mcuhome.ui.theme.MCUHomeTheme

private val SAMPLE_DEVICE = """
device:
  board: esp32c6_devkitc/esp32c6/hpcore
  name: garage-door
  friendly_name: Garage Door
  power_source: mains

# Shared settings for every Wi-Fi device in this project.
packages:
  wifi_common: !include ../../configs/wifi-common.yaml

network:
  wifi:
    ssid: !secret wifi_ssid
    password: !secret wifi_password
  matter:
    enabled: true
    discriminator: !secret garage_door_discriminator
    passcode: !secret garage_door_passcode

hardware:
  peripherals:
    door_contact:
      driver: gpio_input
      pin: 4
      debounce_ms: 30
    relay:
      driver: gpio_output
      active_high: false
""".trimIndent()

/**
 * A page that exists to try the editor out in a real browser: the editor
 * with a sample device file, one diagnostic on the line that has none,
 * and a dialog that has to appear above the editor rather than behind it.
 *
 * It is reachable by typing its route and is not part of the navigation.
 * It goes away with the device screen that carries the finished editor.
 */
@Composable
fun SpikePage(modifier: Modifier = Modifier) {
    val colors = MCUHomeTheme.colors
    val state = rememberTextFieldState(SAMPLE_DEVICE)
    var dialogOpen by remember { mutableStateOf(false) }

    val diagnostics = remember {
        listOf(
            EditorDiagnostic(
                line = 26,
                message = "hardware.peripherals.relay — no pin given; the driver default GPIO5 is used.",
                severity = DiagnosticSeverity.Warning,
            ),
        )
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
                text = "devices/garage-door.yaml",
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
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Resolved model") },
            text = {
                Text(
                    text = "A dialog opened over the editor. Whether it draws above the " +
                        "editor is exactly what this spike has to answer.",
                    fontFamily = MCUHomeTheme.typography.body,
                )
            },
            confirmButton = { TextButton(onClick = { dialogOpen = false }) { Text("Close") } },
        )
    }
}
