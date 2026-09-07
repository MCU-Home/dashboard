// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * What a row's menu offers.
 *
 * Everything here is a command the API already has. Flashing, first-time
 * setup and the device log are not on the list: they are answered with
 * "not available" until the workbench can perform them, and a menu entry
 * that only ever refuses is worse than no entry.
 */
enum class DeviceRowAction(val label: String) {
    Open("Open"),
    Validate("Validate"),
    Build("Build"),
    Rename("Rename…"),
    Clean("Clean build output"),
    Delete("Delete…"),
}

/** The button at the end of a row, and the menu it opens. */
@Composable
fun DeviceRowMenu(
    device: String,
    onAction: (DeviceRowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        MCUHomeIconButton(
            icon = MCUHomeIcons.dots,
            contentDescription = "Actions for $device",
            onClick = { open = true },
            bordered = true,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DeviceRowAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            color = if (action == DeviceRowAction.Delete) {
                                MCUHomeTheme.colors.error
                            } else {
                                MCUHomeTheme.colors.ink
                            },
                            fontFamily = MCUHomeTheme.typography.body,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        open = false
                        onAction(action)
                    },
                )
            }
        }
    }
}
