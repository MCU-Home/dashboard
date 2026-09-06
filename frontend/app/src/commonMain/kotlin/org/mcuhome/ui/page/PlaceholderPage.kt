// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * Stands in for a screen that is not built yet: the title the screen will
 * carry and one line saying what will appear here. Every one of these
 * disappears when its screen arrives.
 */
@Composable
fun PlaceholderPage(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    Column(modifier.fillMaxSize().padding(32.dp)) {
        Text(
            text = title,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.heading,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            text = description,
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
