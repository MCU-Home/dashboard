// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.ConfigState
import org.mcuhome.ui.api.ConfigStatus
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The state pills a device carries, drawn the same way wherever a device
 * appears — in the table's columns and in the rail of its own page. One
 * state, one shape, one wording.
 */
@Composable
fun ConfigStatusPill(status: ConfigStatus, modifier: Modifier = Modifier) {
    when (status.state) {
        ConfigState.Valid -> Pill(
            text = "valid",
            tone = PillTone.Success,
            icon = MCUHomeIcons.check,
            modifier = modifier,
        )

        ConfigState.Errors -> Pill(
            text = countLabel(status.errorCount, "error"),
            tone = PillTone.Error,
            icon = MCUHomeIcons.errorCircle,
            modifier = modifier,
        )

        ConfigState.Warnings -> Pill(
            text = countLabel(status.warningCount, "warning"),
            tone = PillTone.Warning,
            modifier = modifier,
        )

        ConfigState.Unknown -> Pill(text = "not checked", tone = PillTone.Neutral, modifier = modifier)
    }
}

/** Whether the last image was signed. An unbuilt device has nothing to say. */
@Composable
fun SignedStatePill(
    state: SignedState,
    modifier: Modifier = Modifier,
    unknownLabel: String? = null,
) {
    when (state) {
        SignedState.Signed -> Pill(text = "signed", tone = PillTone.Success, modifier = modifier)

        SignedState.Unsigned -> Pill(text = "unsigned", tone = PillTone.Warning, modifier = modifier)

        SignedState.Unknown -> if (unknownLabel == null) {
            EmptyValue(modifier)
        } else {
            Pill(text = unknownLabel, tone = PillTone.Neutral, modifier = modifier)
        }
    }
}

/** The dash shown where a device has nothing to report yet. */
@Composable
fun EmptyValue(modifier: Modifier = Modifier) {
    Text(
        text = "—",
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 13.sp,
        modifier = modifier,
    )
}

/** "1 error", "2 errors" — the plural of a count, where the count is part of the label. */
fun countLabel(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
