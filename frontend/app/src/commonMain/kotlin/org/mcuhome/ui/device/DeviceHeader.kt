// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.FlashMode
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.MCUHomeMenuItem
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.SplitButton
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.MCUHomeTheme

/** The height of the bar that carries the breadcrumb and the actions. */
val DeviceHeaderHeight = 48.dp

/** Everything the header of a device page can start. */
@Immutable
data class DeviceHeaderActions(
    val onOpenDevices: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onValidate: () -> Unit = {},
    val onBuild: (BuildMethod) -> Unit = {},
    val onSign: () -> Unit = {},
    val onFlash: (FlashMode) -> Unit = {},
    val onPairing: () -> Unit = {},
    val onFirstTimeSetup: () -> Unit = {},
    val onClean: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onResolvedModel: () -> Unit = {},
)

/**
 * The bar above the device: where the device sits in the project, which
 * board it is for, whether it has unsaved changes, and everything that
 * can be done with it.
 *
 * The Save button only exists while there is something to save. The
 * design has none at all — a page that saves what it is told to save when
 * it is told to is clearer than one that saves behind the user's back,
 * and Ctrl+S does the same thing without reaching for it.
 */
@Composable
fun DeviceHeader(
    name: String,
    board: String,
    dirty: Boolean,
    saving: Boolean,
    actions: DeviceHeaderActions,
    modifier: Modifier = Modifier,
    defaultBuildMethod: BuildMethod = BuildMethod.Local,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DeviceHeaderHeight)
            .background(colors.surface)
            .bottomBorder(colors.border)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Breadcrumb(name, board, actions.onOpenDevices)
        if (dirty) {
            Pill(text = if (saving) "saving…" else "unsaved changes", tone = PillTone.Accent, dot = true)
            SecondaryButton(text = "Save", onClick = actions.onSave, enabled = !saving)
        }
        Box(Modifier.weight(1f))
        DeviceActions(actions, defaultBuildMethod)
    }
}

@Composable
private fun Breadcrumb(
    name: String,
    board: String,
    onOpenDevices: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Devices",
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 14.sp,
            modifier = Modifier.handCursor().clickable(onClick = onOpenDevices),
        )
        Text(text = "/", color = colors.muted, fontFamily = MCUHomeTheme.typography.body, fontSize = 14.sp)
        Text(
            text = name,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Text(
            text = board,
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.mono,
            fontSize = 12.5.sp,
        )
    }
}

/** The six actions the design puts on the right of the bar. */
@Composable
private fun DeviceActions(actions: DeviceHeaderActions, defaultBuildMethod: BuildMethod) {
    var buildMenu by remember { mutableStateOf(false) }
    var flashMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(text = "Validate", onClick = actions.onValidate, icon = MCUHomeIcons.check)
        Box {
            SplitButton(
                text = "Build",
                onClick = { actions.onBuild(defaultBuildMethod) },
                onOpenMenu = { buildMenu = true },
                icon = MCUHomeIcons.hammer,
                primary = true,
            )
            DropdownMenu(expanded = buildMenu, onDismissRequest = { buildMenu = false }) {
                MCUHomeMenuItem(
                    label = "Build locally",
                    onClick = {
                        buildMenu = false
                        actions.onBuild(BuildMethod.Local)
                    },
                )
                MCUHomeMenuItem(
                    label = "Build on the build server",
                    onClick = {
                        buildMenu = false
                        actions.onBuild(BuildMethod.Remote)
                    },
                )
            }
        }
        SecondaryButton(text = "Sign", onClick = actions.onSign, icon = MCUHomeIcons.key)
        Box {
            SplitButton(
                text = "Flash",
                onClick = { actions.onFlash(FlashMode.Recovery) },
                onOpenMenu = { flashMenu = true },
                icon = MCUHomeIcons.bolt,
            )
            DropdownMenu(expanded = flashMenu, onDismissRequest = { flashMenu = false }) {
                MCUHomeMenuItem(
                    label = "Recovery (USB)",
                    onClick = {
                        flashMenu = false
                        actions.onFlash(FlashMode.Recovery)
                    },
                )
                MCUHomeMenuItem(
                    label = "Over the air",
                    onClick = {
                        flashMenu = false
                        actions.onFlash(FlashMode.Ota)
                    },
                )
            }
        }
        SecondaryButton(text = "Pairing", onClick = actions.onPairing, icon = MCUHomeIcons.qr)
        Box {
            MCUHomeIconButton(
                icon = MCUHomeIcons.dots,
                contentDescription = "More actions",
                onClick = { moreMenu = true },
                bordered = true,
            )
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                MCUHomeMenuItem(
                    label = "Run first-time setup…",
                    onClick = {
                        moreMenu = false
                        actions.onFirstTimeSetup()
                    },
                )
                MCUHomeMenuItem(
                    label = "Show resolved model",
                    onClick = {
                        moreMenu = false
                        actions.onResolvedModel()
                    },
                )
                MCUHomeMenuItem(
                    label = "Clean build output",
                    onClick = {
                        moreMenu = false
                        actions.onClean()
                    },
                )
                MCUHomeMenuItem(
                    label = "Rename…",
                    onClick = {
                        moreMenu = false
                        actions.onRename()
                    },
                )
                MCUHomeMenuItem(
                    label = "Delete…",
                    danger = true,
                    onClick = {
                        moreMenu = false
                        actions.onDelete()
                    },
                )
            }
        }
    }
}

private fun Modifier.bottomBorder(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawLine(
        color = color,
        start = Offset(0f, size.height - stroke / 2f),
        end = Offset(size.width, size.height - stroke / 2f),
        strokeWidth = stroke,
    )
}
