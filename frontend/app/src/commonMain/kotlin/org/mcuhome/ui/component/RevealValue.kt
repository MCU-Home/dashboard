// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * A value the interface holds back until it is asked for it.
 *
 * [revealed] is null for as long as nobody has asked, and the row shows
 * [masked] — the dots the server sent instead of the value. There is no
 * client-side unmasking to be had: until the reveal request comes back
 * there is nothing here to unmask.
 */
@Composable
fun RevealValue(
    masked: String,
    revealed: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    Text(
        text = revealed ?: masked,
        color = if (revealed == null) colors.muted else colors.ink,
        fontFamily = MCUHomeTheme.typography.mono,
        fontSize = 13.sp,
        modifier = modifier,
    )
}
