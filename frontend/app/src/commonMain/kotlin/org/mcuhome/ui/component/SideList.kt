// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/** The height of a side list's own header row. */
private val SideListHeaderHeight = 52.dp

/** The height of one entry; comfortably above the smallest target a pointer should hit. */
private val SideListItemHeight = 36.dp

/**
 * The column on the left of a screen that picks one thing out of a few:
 * the shared configuration files today.
 *
 * It carries its own heading with the action that adds an entry, scrolls
 * when the list outgrows the window, and ends in the sentence that says
 * what the list is — which is where the design puts the explanation
 * rather than above the editor.
 */
@Composable
fun SideList(
    title: String,
    width: Dp,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MCUHomeTheme.colors
    val scroll = rememberScrollState()
    Column(modifier.width(width).fillMaxHeight().background(colors.surface).rightBorder()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(SideListHeaderHeight).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.heading,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Box(Modifier.weight(1f))
            action()
        }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll), content = content)
            ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        if (footer != null) {
            Text(
                text = footer,
                color = MCUHomeTheme.colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

/**
 * One entry of a [SideList]: an icon, what it is called, and the one
 * number that says how much it matters. The selected entry carries the
 * accent tint and a bar on its leading edge.
 */
@Composable
fun SideListItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: String? = null,
    selected: Boolean = false,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SideListItemHeight)
            .then(if (selected) Modifier.background(colors.accentTint) else Modifier)
            .handCursor()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colors.accentOnTint else colors.muted,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
            )
        }
    }
}
