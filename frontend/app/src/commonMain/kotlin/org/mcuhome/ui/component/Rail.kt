// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The column of facts on the right of a working screen: what is known
 * about the file in the editor beside it.
 *
 * The device page and the shared-configuration page both have one, and
 * both fill it with the same kind of block — a heading in small capitals
 * and a few rows of label and value — so the frame is here and only the
 * blocks differ.
 */
@Composable
fun SideRail(
    width: Dp,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Box(modifier.width(width).fillMaxHeight().background(colors.surface).leftBorder()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(scroll), content = content)
        ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** One block of a rail: a heading in small capitals and what it says. */
@Composable
fun RailSection(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bottomBorder()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.uppercase(),
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
            )
            Box(Modifier.weight(1f))
            action()
        }
        content()
    }
}

/** One line of a block: what it is on the left, what it says on the right. */
@Composable
fun KeyValueRow(
    label: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MCUHomeTheme.colors.muted,
            fontFamily = if (mono) MCUHomeTheme.typography.mono else MCUHomeTheme.typography.body,
            fontSize = 12.5.sp,
        )
        Box(Modifier.weight(1f))
        value()
    }
}

/** The right-hand side of a [KeyValueRow] when it is nothing but text. */
@Composable
fun RailValue(
    text: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    Text(
        text = text,
        color = MCUHomeTheme.colors.ink,
        fontFamily = if (mono) MCUHomeTheme.typography.mono else MCUHomeTheme.typography.body,
        fontSize = 12.5.sp,
        modifier = modifier,
    )
}

/** The line that separates a rail or a side list from the column beside it. */
@Composable
fun Modifier.leftBorder(): Modifier {
    val colors = MCUHomeTheme.colors
    return drawBehind {
        val stroke = 1.dp.toPx()
        drawLine(colors.border, Offset(stroke / 2f, 0f), Offset(stroke / 2f, size.height), stroke)
    }
}

/** The line that separates a rail or a side list from the column beside it. */
@Composable
fun Modifier.rightBorder(): Modifier {
    val colors = MCUHomeTheme.colors
    return drawBehind {
        val stroke = 1.dp.toPx()
        val x = size.width - stroke / 2f
        drawLine(colors.border, Offset(x, 0f), Offset(x, size.height), stroke)
    }
}

/** The line under a block, a header row or a table row. */
@Composable
fun Modifier.bottomBorder(): Modifier {
    val colors = MCUHomeTheme.colors
    return drawBehind {
        val stroke = 1.dp.toPx()
        val y = size.height - stroke / 2f
        drawLine(colors.border, Offset(0f, y), Offset(size.width, y), stroke)
    }
}
