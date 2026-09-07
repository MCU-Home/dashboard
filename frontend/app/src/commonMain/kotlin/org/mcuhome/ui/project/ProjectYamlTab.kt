// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.editor.EditorDocument
import org.mcuhome.ui.editor.SaveConflictNotice
import org.mcuhome.ui.editor.YamlEditor
import org.mcuhome.ui.theme.MCUHomeTheme

private val EditorShape = RoundedCornerShape(10.dp)

/** What the project file's own bar can start. */
@Immutable
data class ProjectYamlActions(val onSave: () -> Unit, val onReload: () -> Unit, val onOverwrite: () -> Unit)

/**
 * `mcuhome.yaml` itself, in the same editor the device screen uses.
 *
 * Everything the Options table writes ends up in this file, and this is
 * where the parts the table has no editor for — the nested maps — are
 * changed. A write that lost a race is answered with the same choice as
 * anywhere else.
 */
@Composable
fun ProjectYamlTab(
    path: String,
    text: TextFieldState,
    document: EditorDocument?,
    actions: ProjectYamlActions,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = path,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.mono,
                fontSize = 13.sp,
            )
            if (document?.dirty == true) {
                Pill(
                    text = if (document.saving) "saving…" else "unsaved changes",
                    tone = PillTone.Accent,
                    dot = true,
                )
            }
            Box(Modifier.weight(1f))
            PrimaryButton(text = "Save", onClick = actions.onSave, enabled = document?.canSave == true)
        }
        if (document?.conflict != null) {
            SaveConflictNotice(
                onReload = actions.onReload,
                onOverwrite = actions.onOverwrite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(EditorShape)
                .background(colors.surface)
                .border(1.dp, colors.border, EditorShape),
        ) {
            YamlEditor(state = text, diagnostics = emptyList(), modifier = Modifier.fillMaxSize())
        }
    }
}
