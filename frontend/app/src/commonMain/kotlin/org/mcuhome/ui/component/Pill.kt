// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The roles a pill can carry. A tone is a meaning, not a color: which
 * three brand tokens it resolves to is decided in one place, so a pill
 * can never introduce a color of its own.
 */
enum class PillTone {
    Success,
    Warning,
    Error,
    Info,
    Accent,
    Neutral,
}

/** The three tokens one tone resolves to: the fill, the outline, and the text on it. */
@Immutable
data class PillColors(val fill: Color, val outline: Color, val content: Color)

@Composable
fun pillColors(tone: PillTone): PillColors {
    val colors = MCUHomeTheme.colors
    return when (tone) {
        PillTone.Success -> PillColors(colors.successTint, colors.successTintBorder, colors.successOnTint)
        PillTone.Warning -> PillColors(colors.warningTint, colors.warningTintBorder, colors.warningOnTint)
        PillTone.Error -> PillColors(colors.errorTint, colors.errorTintBorder, colors.errorOnTint)
        PillTone.Info -> PillColors(colors.infoTint, colors.infoTintBorder, colors.infoOnTint)
        PillTone.Accent -> PillColors(colors.accentTint, colors.accentTintBorder, colors.accentOnTint)
        PillTone.Neutral -> PillColors(colors.backgroundAlt, colors.border, colors.muted)
    }
}

private val PillShape = RoundedCornerShape(11.dp)

/**
 * The small rounded label the interface states a status with: the Config
 * column's "valid" or "2 errors", the Signed column, the "building" pill
 * of a running build.
 *
 * At most one of [icon] and [dot] is drawn in front of the text — an icon
 * where the state has one (a check, an error circle), a filled dot where
 * the design marks the state with color alone.
 */
@Composable
fun Pill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dot: Boolean = false,
) {
    val pill = pillColors(tone)
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(pill.fill)
            .border(width = 1.dp, color = pill.outline, shape = PillShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = pill.content,
                modifier = Modifier.size(13.dp),
            )
        } else if (dot) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(pill.content),
            )
        }
        Text(
            text = text,
            color = pill.content,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.sp,
        )
    }
}
