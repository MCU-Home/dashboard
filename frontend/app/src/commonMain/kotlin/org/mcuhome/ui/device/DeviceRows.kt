// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.SurfaceCard
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.MCUHomeColors
import org.mcuhome.ui.theme.MCUHomeTheme

/** The height of one row: two lines of text and a finger's target. */
private val PhoneRowHeight = 64.dp

/**
 * The device list on a phone: one row per device instead of a table.
 *
 * A table needs columns and a phone has room for one. What is left is
 * what a user scanning the list is looking for — the name, what it runs
 * on, and the one state that matters most right now, with a dot that
 * repeats it in colour so the list can be read without reading it.
 */
@Composable
fun DeviceRows(
    devices: List<DeviceSummary>,
    onOpenDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    SurfaceCard(modifier) {
        if (devices.isEmpty()) {
            Text(
                text = "No device matches this filter.",
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
                modifier = Modifier.padding(all = 20.dp),
            )
        }
        devices.forEachIndexed { index, device ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            PhoneDeviceRow(device, onClick = { onOpenDevice(device.name) })
        }
    }
}

@Composable
private fun PhoneDeviceRow(device: DeviceSummary, onClick: () -> Unit) {
    val colors = MCUHomeTheme.colors
    val state = deviceRowState(device)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PhoneRowHeight)
            .handCursor().clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.stateDot(state)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Text(
                text = "${shortBoard(device.board)} · ${networkLabel(device)}",
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Pill(text = deviceRowLabel(device), tone = deviceRowTone(state))
        Icon(
            imageVector = MCUHomeIcons.chevronRight,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The colour of the dot in front of a row: the state's own, said once more. */
private fun MCUHomeColors.stateDot(state: DeviceRowState): Color = when (state) {
    DeviceRowState.Errors, DeviceRowState.Failed -> error
    DeviceRowState.Building -> accent
    DeviceRowState.Warnings -> warning
    DeviceRowState.NotBuilt, DeviceRowState.Unsigned, DeviceRowState.Signed -> success
    DeviceRowState.Unchecked -> pinGray
}
