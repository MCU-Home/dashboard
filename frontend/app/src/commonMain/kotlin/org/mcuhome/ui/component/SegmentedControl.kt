// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

private val OuterShape = RoundedCornerShape(8.dp)
private val SegmentShape = RoundedCornerShape(6.dp)

/** The height of one segment; the control adds its own two pixels of padding around them. */
private val SegmentHeight = 32.dp

/**
 * A row of mutually exclusive choices: the device table's filter (All,
 * Errors, Not built, Unsigned) and the New device dialog's starter.
 *
 * The whole set is visible at once rather than hidden behind a dropdown —
 * with four short options that is one click instead of two, and the
 * options themselves say what the screen can be narrowed to.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier
            .height(SegmentHeight + 4.dp)
            .clip(OuterShape)
            .background(colors.backgroundAlt)
            .border(width = 1.dp, color = colors.border, shape = OuterShape)
            .padding(all = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            Segment(
                label = label(option),
                active = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun Segment(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier = Modifier
            .height(SegmentHeight)
            .clip(SegmentShape)
            .then(if (active) Modifier.background(colors.surface) else Modifier)
            .then(if (active) Modifier.border(1.dp, colors.border, SegmentShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) colors.ink else colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
        )
    }
}
