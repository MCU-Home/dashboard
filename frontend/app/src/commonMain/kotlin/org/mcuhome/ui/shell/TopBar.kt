// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.brand.MCUHomeMark
import org.mcuhome.ui.theme.MCUHomeTheme

/** The height the design gives the top bar. */
val TopBarHeight = 48.dp

/**
 * The bar across the top of every screen: the mark and wordmark, the name
 * of the open project, the navigation, and — on the right — the state of
 * the running jobs and of the connection to the server.
 *
 * The jobs chip and the connection indicator show fixed values for now;
 * they are wired to the API once it exists.
 */
@Composable
fun TopBar(
    projectName: String,
    current: Destination?,
    onNavigate: (Destination) -> Unit,
    runningJobs: Int,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .background(colors.surface)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = colors.border,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MCUHomeMark(Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "MCUHome",
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.heading,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "· $projectName",
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
        )

        Spacer(Modifier.width(24.dp))
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Destination.entries.forEach { destination ->
                NavigationItem(
                    destination = destination,
                    active = destination == current,
                    onClick = { onNavigate(destination) },
                )
            }
        }

        Spacer(Modifier.weight(1f))
        JobsChip(runningJobs)
        Spacer(Modifier.width(16.dp))
        ConnectionState(connected)
    }
}

@Composable
private fun NavigationItem(destination: Destination, active: Boolean, onClick: () -> Unit) {
    val colors = MCUHomeTheme.colors
    // No hover or press indication: the design marks the active item with
    // the accent underline and nothing else.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp)
            .drawBehind {
                if (active) {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = colors.accent,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = destination.label,
            color = if (active) colors.ink else colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun JobsChip(runningJobs: Int) {
    val colors = MCUHomeTheme.colors
    val pulse = rememberInfiniteTransition(label = "jobs-pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "jobs-dot-alpha",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accentTint)
            .border(1.dp, colors.accentTintBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(colors.accent)
        )
        Text(
            text = "$runningJobs running",
            color = colors.accentOnTint,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ConnectionState(connected: Boolean) {
    val colors = MCUHomeTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (connected) colors.success else colors.error)
        )
        Text(
            text = if (connected) "connected" else "disconnected",
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
        )
    }
}
