// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * One line of a dropdown menu: the row menu of a table, the build
 * methods behind the Build button, the values of an option that has a
 * fixed vocabulary.
 *
 * Material's own entry brings its own type and its own colors; this one
 * carries the interface's body face and the ink token, marks a
 * destructive entry in the error token, and shows the pointer that says
 * it acts.
 */
@Composable
fun MCUHomeMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (danger) MCUHomeTheme.colors.error else MCUHomeTheme.colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
        },
        onClick = onClick,
        modifier = modifier.handCursor(enabled),
        enabled = enabled,
    )
}
