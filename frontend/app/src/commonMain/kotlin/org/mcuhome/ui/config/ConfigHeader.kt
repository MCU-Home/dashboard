// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.theme.MCUHomeTheme

/** The height the header bar of an editing screen shares with the device page. */
private val ConfigHeaderHeight = 48.dp

/** What the header of the shared-configuration screen can start. */
@Immutable
data class ConfigHeaderActions(val onValidateUsers: () -> Unit, val onSave: () -> Unit)

/**
 * The bar above the fragment in the editor: where the file sits, whether
 * it holds anything unwritten, and the two things that can be done with
 * it.
 *
 * "Validate users" is the only check a fragment has: half of it only
 * makes sense inside a device that includes it, so what is checked are
 * the devices, not the file.
 */
@Composable
fun ConfigHeader(
    fileName: String,
    dirty: Boolean,
    saving: Boolean,
    validating: Boolean,
    actions: ConfigHeaderActions,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ConfigHeaderHeight)
            .background(colors.surface)
            .bottomBorder()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "Configs", color = colors.muted, fontFamily = MCUHomeTheme.typography.body, fontSize = 14.sp)
        Text(text = "/", color = colors.muted, fontFamily = MCUHomeTheme.typography.body, fontSize = 14.sp)
        Text(
            text = fileName,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        if (dirty) {
            Pill(text = if (saving) "saving…" else "unsaved changes", tone = PillTone.Accent, dot = true)
        }
        Box(Modifier.weight(1f))
        SecondaryButton(
            text = if (validating) "Validating…" else "Validate users",
            onClick = actions.onValidateUsers,
            icon = MCUHomeIcons.check,
            enabled = !validating,
        )
        PrimaryButton(text = "Save", onClick = actions.onSave, enabled = dirty && !saving)
    }
}
