// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

/** The width of the small dialogs: wide enough for one field and one sentence. */
val PromptWidth = 440.dp

/**
 * The small dialog that asks one question: a new name, a new file, a
 * confirmation before something is thrown away.
 *
 * Escape closes it and Enter runs [onSubmit], so the whole exchange can
 * be finished on the keyboard.
 */
@Composable
fun PromptCard(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ModalCard(onDismissRequest = onDismiss, modifier = modifier.width(PromptWidth), onSubmit = onSubmit) {
        Column(Modifier.padding(all = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title,
                color = MCUHomeTheme.colors.ink,
                fontFamily = MCUHomeTheme.typography.heading,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            content()
        }
    }
}

/** The row a prompt ends in: the way out on the left of the way on. */
@Composable
fun PromptActions(
    confirm: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(text = "Cancel", onClick = onDismiss)
            if (danger) {
                SecondaryButton(text = confirm, onClick = onConfirm, enabled = enabled, danger = true)
            } else {
                PrimaryButton(text = confirm, onClick = onConfirm, enabled = enabled)
            }
        }
    }
}
