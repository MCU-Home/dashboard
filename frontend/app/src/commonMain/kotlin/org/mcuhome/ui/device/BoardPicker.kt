// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.BoardInfo
import org.mcuhome.ui.api.NetworkTransport
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.ThinVerticalScrollbar
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.MCUHomeTheme

private val ListShape = RoundedCornerShape(8.dp)
private val BoardRowHeight = 34.dp

/**
 * The boards that match what was typed, in the order the registry lists
 * them: the ones that can be built for first, the planned ones after.
 *
 * A board is found by its target, its name or its vendor, because that is
 * what a user remembers of it — "nrf", "ESP32", "Nordic" all lead to the
 * same place.
 */
fun filterBoards(boards: List<BoardInfo>, query: String): List<BoardInfo> {
    val needle = query.trim()
    val matching = if (needle.isEmpty()) {
        boards
    } else {
        boards.filter {
            it.target.contains(needle, ignoreCase = true) ||
                it.displayName.contains(needle, ignoreCase = true) ||
                it.vendor.contains(needle, ignoreCase = true)
        }
    }
    return matching.sortedBy { it.planned }
}

/**
 * The list under the board search.
 *
 * A board MCUHome cannot build for yet is listed rather than hidden, in
 * muted text with a "planned" pill and no reaction to a click: a picker
 * that says why something is missing answers the question a short list
 * only raises.
 */
@Composable
fun BoardList(
    boards: List<BoardInfo>,
    selected: String?,
    onSelect: (BoardInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ListShape)
                .border(width = 1.dp, color = colors.border, shape = ListShape)
                .heightIn(max = 176.dp)
                .verticalScroll(scroll),
        ) {
            if (boards.isEmpty()) {
                Text(
                    text = "No board matches that.",
                    color = colors.muted,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(all = 12.dp),
                )
            }
            boards.forEachIndexed { index, board ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                BoardRow(board = board, selected = board.target == selected, onSelect = { onSelect(board) })
            }
        }
        ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun BoardRow(
    board: BoardInfo,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BoardRowHeight)
            .background(if (selected) colors.accentTint else colors.surface)
            .handCursor(!board.planned).clickable(enabled = !board.planned, onClick = onSelect)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(BoardRowHeight)
                .background(if (selected) colors.accent else colors.surface),
        )
        Text(
            text = board.target,
            color = if (board.planned) colors.muted else colors.ink,
            fontFamily = MCUHomeTheme.typography.mono,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
        Box(Modifier.weight(1f))
        if (board.planned) {
            Pill(text = "planned", tone = PillTone.Neutral)
        } else {
            Text(
                text = boardDescription(board),
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
            )
        }
    }
}

/** What a board is, in one line: its name and the networks it can join. */
fun boardDescription(board: BoardInfo): String {
    val transports = board.transports.joinToString(", ") {
        when (it) {
            NetworkTransport.Thread -> "Thread"
            NetworkTransport.WiFi -> "Wi-Fi"
            NetworkTransport.Ethernet -> "Ethernet"
        }
    }
    return listOf(board.displayName, transports).filter { it.isNotEmpty() }.joinToString(" · ")
}
