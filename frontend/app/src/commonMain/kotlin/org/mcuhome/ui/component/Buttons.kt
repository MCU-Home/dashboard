// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The height every control in a toolbar row shares.
 *
 * The design draws its controls a few pixels shorter; they are unified at
 * this height so that every one of them is comfortably larger than the
 * smallest target a pointer should have to hit.
 */
val ControlHeight = 36.dp

/** The smallest square an icon-only control is drawn in. */
val IconButtonSize = 32.dp

private val ButtonShape = RoundedCornerShape(8.dp)

/**
 * The one action a screen leads with: "New device", "Create device". Its
 * fill is the brand accent, so exactly one of these is on screen at a time.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = MCUHomeTheme.colors
    val content = if (enabled) colors.surface else colors.muted
    Row(
        modifier = modifier
            .height(ControlHeight)
            .clip(ButtonShape)
            .background(if (enabled) colors.accent else colors.backgroundAlt)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
        }
        Text(
            text = text,
            color = content,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

/** An action beside the primary one: "Cancel", "Delete", a menu entry that opens a dialog. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier = modifier
            .height(ControlHeight)
            .clip(ButtonShape)
            .background(colors.surface)
            .border(width = 1.dp, color = colors.border, shape = ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (danger) colors.error else colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

/** A word that acts, with nothing drawn around it: "Clear finished". */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = MCUHomeTheme.colors.accentOnTint,
        fontFamily = MCUHomeTheme.typography.body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        modifier = modifier
            .clip(ButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * An icon on its own that acts: a row's menu button, the cancel of a
 * running job, the close of a dialog. [bordered] draws the outline the
 * table's row menu has; the buttons inside a popover have none.
 */
@Composable
fun MCUHomeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
    tint: Color = MCUHomeTheme.colors.muted,
) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = IconButtonSize, minHeight = IconButtonSize)
            .size(IconButtonSize)
            .clip(ButtonShape)
            .then(if (bordered) Modifier.border(1.dp, colors.border, ButtonShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}
