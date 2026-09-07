// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mcuhome.ui.component.Notice
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SecondaryButton

/**
 * What the interface says when a write lost a race: the file moved on
 * somewhere else while it was open here.
 *
 * Neither version is thrown away without being asked for — the choice is
 * the whole point, and it reads the same on every screen that has an
 * editor.
 */
@Composable
fun SaveConflictNotice(
    onReload: () -> Unit,
    onOverwrite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Notice(
            tone = PillTone.Warning,
            title = "This file changed somewhere else while it was open here",
            message = "Reload takes the other version and drops what was typed here; " +
                "overwrite keeps this text and writes it over the other one.",
            modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.padding(start = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(text = "Reload", onClick = onReload)
            PrimaryButton(text = "Overwrite", onClick = onOverwrite)
        }
    }
}
