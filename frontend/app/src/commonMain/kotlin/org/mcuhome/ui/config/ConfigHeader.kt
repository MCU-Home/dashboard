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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.shell.LocalWindowSize
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
    onBack: (() -> Unit)? = null,
) {
    val colors = MCUHomeTheme.colors
    val window = LocalWindowSize.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ConfigHeaderHeight)
            .background(colors.surface)
            .bottomBorder()
            .padding(horizontal = if (onBack == null) 20.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Where the list is a screen of its own rather than a column
        // beside this one, the trail back to it is a button.
        if (onBack != null) {
            MCUHomeIconButton(
                icon = MCUHomeIcons.chevronLeft,
                contentDescription = "Back to the shared configurations",
                onClick = onBack,
                tint = colors.ink,
                size = 40.dp,
            )
        } else if (window.expanded) {
            // The trail is dropped where the list beside the editor
            // already says where the file is and the width is needed for
            // the name itself.
            Text(text = "Configs", color = colors.muted, fontFamily = MCUHomeTheme.typography.body, fontSize = 14.sp)
            Text(text = "/", color = colors.muted, fontFamily = MCUHomeTheme.typography.body, fontSize = 14.sp)
        }
        Text(
            text = fileName,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            // The name gives way before the actions do: a bar whose Save
            // button is off the edge is a bar that cannot save.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dirty) {
            Pill(text = if (saving) "saving…" else "unsaved changes", tone = PillTone.Accent, dot = true)
        }
        SecondaryButton(
            text = when {
                validating -> "Validating…"
                window.expanded -> "Validate users"
                else -> "Validate"
            },
            onClick = actions.onValidateUsers,
            icon = MCUHomeIcons.check,
            enabled = !validating,
        )
        PrimaryButton(text = "Save", onClick = actions.onSave, enabled = dirty && !saving)
    }
}
