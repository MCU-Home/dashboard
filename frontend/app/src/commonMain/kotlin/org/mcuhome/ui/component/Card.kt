// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

private val CardShape = RoundedCornerShape(10.dp)

/** The height of a table's header row. */
val TableHeaderHeight = 38.dp

/**
 * The raised sheet a screen puts its content on: the device table, the
 * jobs popover, the board list. One surface, one border, one radius —
 * everything a screen shows sits on one of these.
 */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MCUHomeTheme.colors
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(colors.surface)
            .border(width = 1.dp, color = colors.border, shape = CardShape),
        content = content,
    )
}

/** The header row of a table: the alternate background and the line under it. */
@Composable
fun TableHeaderRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier
            .height(TableHeaderHeight)
            .background(colors.backgroundAlt)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One column heading. [ascending] says how the table is sorted by this
 * column — null when it is sorted by another one, in which case no
 * indicator is drawn. Clicking anywhere in the heading sorts by it.
 */
@Composable
fun TableHeaderCell(
    label: String,
    ascending: Boolean?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier.fillMaxHeight().handCursor().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            color = if (ascending == null) colors.muted else colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = if (ascending == null) FontWeight.Normal else FontWeight.SemiBold,
            fontSize = 12.5.sp,
        )
        if (ascending != null) {
            Icon(
                imageVector = if (ascending) MCUHomeIcons.chevronUp else MCUHomeIcons.chevronDown,
                contentDescription = if (ascending) "sorted ascending" else "sorted descending",
                tint = colors.muted,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}
