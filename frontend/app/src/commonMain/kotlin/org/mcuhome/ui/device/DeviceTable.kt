// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.DeviceSummary
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.SurfaceCard
import org.mcuhome.ui.component.TableHeaderCell
import org.mcuhome.ui.component.TableHeaderRow
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.formatTimestamp

/** The share of the table's width each column takes. */
private val ColumnWeights = mapOf(
    DeviceColumn.Name to 22f,
    DeviceColumn.Board to 20f,
    DeviceColumn.Config to 13f,
    DeviceColumn.Build to 17f,
    DeviceColumn.Signed to 10f,
    DeviceColumn.Network to 12f,
)

/** The column that holds a row's menu button, which has no heading. */
private val MenuColumnWidth = 52.dp

private val RowHeight = 49.dp

/**
 * The device table: one row per device, with the state columns the design
 * puts side by side, and a header whose columns sort it.
 *
 * Clicking a row anywhere but on its menu opens the device, which is the
 * menu's own first entry — one action, reported through one callback, so
 * the row and the menu can never mean different things by it.
 *
 * [columns] is what the window has room for. A narrower window is given
 * fewer columns rather than thinner ones; what a dropped column said is
 * folded into the one beside it where it has to be — the Build cell adds
 * "· signed" once the Signed column is gone.
 */
@Composable
fun DeviceTable(
    devices: List<DeviceSummary>,
    sort: DeviceSort,
    nowEpochMillis: Long,
    onSort: (DeviceColumn) -> Unit,
    onRowAction: (DeviceRowAction, DeviceSummary) -> Unit,
    modifier: Modifier = Modifier,
    columns: List<DeviceColumn> = DeviceColumn.entries,
) {
    val colors = MCUHomeTheme.colors
    SurfaceCard(modifier) {
        TableHeaderRow(Modifier.fillMaxWidth()) {
            columns.forEach { column ->
                TableHeaderCell(
                    label = column.label,
                    ascending = if (sort.column == column) sort.ascending else null,
                    onClick = { onSort(column) },
                    modifier = Modifier.weight(ColumnWeights.getValue(column)),
                )
            }
            Box(Modifier.width(MenuColumnWidth))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

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
            DeviceRow(
                device = device,
                nowEpochMillis = nowEpochMillis,
                columns = columns,
                onRowAction = { action -> onRowAction(action, device) },
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceSummary,
    nowEpochMillis: Long,
    columns: List<DeviceColumn>,
    onRowAction: (DeviceRowAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .handCursor().clickable { onRowAction(DeviceRowAction.Open) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            DeviceCell(
                column = column,
                device = device,
                nowEpochMillis = nowEpochMillis,
                withSignedState = DeviceColumn.Signed !in columns,
            )
        }
        Box(Modifier.width(MenuColumnWidth), contentAlignment = Alignment.CenterEnd) {
            DeviceRowMenu(device.name, onAction = onRowAction)
        }
    }
}

@Composable
private fun RowScope.DeviceCell(
    column: DeviceColumn,
    device: DeviceSummary,
    nowEpochMillis: Long,
    withSignedState: Boolean,
) {
    val colors = MCUHomeTheme.colors
    val modifier = Modifier.weight(ColumnWeights.getValue(column))
    when (column) {
        DeviceColumn.Name -> NameCell(device, modifier)

        DeviceColumn.Board -> Text(
            text = device.board,
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.mono,
            fontSize = 12.sp,
            modifier = modifier,
        )

        DeviceColumn.Config -> Box(modifier) { ConfigStatusPill(device.config) }

        DeviceColumn.Build -> BuildCell(device, nowEpochMillis, withSignedState, modifier)

        DeviceColumn.Signed -> Box(modifier) { SignedStatePill(device.signed) }

        DeviceColumn.Network -> Text(
            text = networkLabel(device),
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
            modifier = modifier,
        )
    }
}

@Composable
private fun NameCell(device: DeviceSummary, modifier: Modifier = Modifier) {
    val colors = MCUHomeTheme.colors
    Column(modifier) {
        Text(
            text = device.name,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp,
        )
        Text(
            text = device.friendlyName,
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun BuildCell(
    device: DeviceSummary,
    nowEpochMillis: Long,
    withSignedState: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    val build = device.build
    if (build.state == BuildState.Building) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(text = "building", tone = PillTone.Accent, dot = true)
            val progress = build.progress
            if (progress != null) {
                Text(
                    text = "${progress.done}/${progress.total}",
                    color = colors.muted,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }
    if (build.state == BuildState.NeverBuilt) {
        Box(modifier) { EmptyValue() }
        return
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (build.state == BuildState.Failed) "failed" else "built",
                color = if (build.state == BuildState.Failed) colors.error else colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            val signed = if (withSignedState) signedLabel(device.signed) else null
            if (signed != null) {
                Text(
                    text = " · $signed",
                    color = if (device.signed == SignedState.Signed) colors.ink else colors.warningOnTint,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 13.sp,
                )
            }
        }
        val finishedAt = build.finishedAtEpochMillis
        if (finishedAt != null) {
            Text(
                text = listOfNotNull(
                    formatTimestamp(finishedAt, nowEpochMillis),
                    build.method?.name?.lowercase(),
                ).joinToString(" · "),
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
            )
        }
    }
}

/** What the Build cell adds about the image once the Signed column is gone. */
private fun signedLabel(state: SignedState): String? = when (state) {
    SignedState.Signed -> "signed"
    SignedState.Unsigned -> "unsigned"
    SignedState.Unknown -> null
}
