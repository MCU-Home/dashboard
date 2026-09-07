// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.component.PromptActions
import org.mcuhome.ui.component.PromptCard
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The question a screen with an editor asks before it is left: the text
 * on screen was never written anywhere.
 *
 * Staying is the safe answer and therefore the one Enter and Escape both
 * give; leaving is the one that has to be pressed.
 */
@Composable
fun UnsavedChangesDialog(onStay: () -> Unit, onLeave: () -> Unit) {
    PromptCard(title = "Leave without saving?", onDismiss = onStay, onSubmit = onStay) {
        Text(
            text = "This file has changes that were never written. Leaving the page throws them away.",
            color = MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
        )
        PromptActions(
            confirm = "Discard and leave",
            enabled = true,
            onDismiss = onStay,
            onConfirm = onLeave,
            modifier = Modifier.fillMaxWidth(),
            danger = true,
        )
    }
}
