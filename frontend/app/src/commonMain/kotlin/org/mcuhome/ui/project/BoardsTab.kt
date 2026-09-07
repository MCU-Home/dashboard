// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.BoardInfo
import org.mcuhome.ui.api.BoardRegistry
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.SurfaceCard
import org.mcuhome.ui.component.TableHeaderRow
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.component.transportLabel
import org.mcuhome.ui.theme.MCUHomeTheme

private val BoardRowHeight = 52.dp
private val PlannedColumnWidth = 110.dp

/** The three content columns share the row as weights. */
private const val TARGET_COLUMN_WEIGHT = 0.38f
private const val DESCRIPTION_COLUMN_WEIGHT = 0.37f
private const val NETWORK_COLUMN_WEIGHT = 0.25f

/**
 * What MCUHome can build for.
 *
 * A board that is planned but not brought up yet is listed with that
 * said out loud rather than left out — the New device dialog offers the
 * same list, and a short list with no explanation is the worse answer.
 */
@Composable
fun BoardsTab(registry: BoardRegistry, modifier: Modifier = Modifier) {
    SurfaceCard(modifier) {
        TableHeaderRow {
            HeaderCell("Board", Modifier.weight(TARGET_COLUMN_WEIGHT))
            HeaderCell("Description", Modifier.weight(DESCRIPTION_COLUMN_WEIGHT))
            HeaderCell("Network", Modifier.weight(NETWORK_COLUMN_WEIGHT))
            Box(Modifier.width(PlannedColumnWidth))
        }
        registry.boards.forEachIndexed { index, board ->
            BoardRow(board, last = index == registry.boards.lastIndex)
        }
    }
}

@Composable
private fun HeaderCell(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 12.5.sp,
        modifier = modifier,
    )
}

@Composable
private fun BoardRow(board: BoardInfo, last: Boolean) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = BoardRowHeight)
            .then(if (last) Modifier else Modifier.bottomBorder())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = board.target,
            color = if (board.planned) colors.muted else colors.ink,
            fontFamily = MCUHomeTheme.typography.mono,
            fontSize = 13.sp,
            modifier = Modifier.weight(TARGET_COLUMN_WEIGHT).padding(end = 12.dp),
        )
        Column(Modifier.weight(DESCRIPTION_COLUMN_WEIGHT).padding(end = 12.dp)) {
            Text(
                text = board.displayName,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
            Text(
                text = listOfNotNull(board.vendor, board.plannedReason).joinToString(" · "),
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
            )
        }
        Text(
            text = board.transports.joinToString(", ") { transportLabel(it) }.ifEmpty { "—" },
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
            modifier = Modifier.weight(NETWORK_COLUMN_WEIGHT),
        )
        Box(Modifier.width(PlannedColumnWidth), contentAlignment = Alignment.CenterStart) {
            if (board.planned) {
                Pill(text = "planned", tone = PillTone.Neutral)
            }
        }
    }
}
