// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.MCUHomeTheme

/** The height of one entry: a finger's target, not a pointer's. */
private val MenuEntryHeight = 48.dp

/** The accent bar that marks the open screen, as wide as the top bar's underline is tall. */
private val ActiveMarkerWidth = 3.dp

private val ActiveMarkerShape = RoundedCornerShape(2.dp)

/**
 * The navigation of a phone: the four screens of the top bar, listed
 * under it instead of beside it.
 *
 * It is drawn into the page rather than into a popup so that it hangs off
 * the bar that opened it whatever the window does, and it is dismissed by
 * touching anything else — the sheet is a menu, and a menu that has to be
 * closed twice is a nuisance.
 */
@Composable
fun NavigationMenu(
    current: Destination?,
    onNavigate: (Destination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    val dismissInteraction = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = dismissInteraction, indication = null, onClick = onDismiss),
        )
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surface)
                .bottomBorder(),
        ) {
            Destination.entries.forEach { destination ->
                MenuEntry(
                    destination = destination,
                    active = destination == current,
                    onClick = { onNavigate(destination) },
                )
            }
        }
    }
}

@Composable
private fun MenuEntry(
    destination: Destination,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MenuEntryHeight)
            .handCursor().clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(ActiveMarkerWidth)
                    .height(MenuEntryHeight / 2)
                    .clip(ActiveMarkerShape)
                    .background(colors.accent),
            )
        }
        Text(
            text = destination.label,
            color = if (active) colors.ink else colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 15.sp,
            modifier = Modifier.padding(start = ActiveMarkerWidth + 12.dp),
        )
    }
}
