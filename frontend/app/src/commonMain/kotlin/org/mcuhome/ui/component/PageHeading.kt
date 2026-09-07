// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The first line of a full-width screen: what the screen is, what it is
 * about, and the controls that act on the whole of it.
 *
 * [meta] is set in the monospace role — it is always an identifier, a
 * path or a count, never prose.
 */
@Composable
fun PageHeading(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.heading,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
        )
        if (meta != null) {
            Text(
                text = meta,
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.mono,
                fontSize = 13.sp,
            )
        }
        Box(Modifier.weight(1f))
        trailing()
    }
}

/** The sentence under a heading that says what the screen writes and where. */
@Composable
fun PageNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 13.sp,
        modifier = modifier,
    )
}
