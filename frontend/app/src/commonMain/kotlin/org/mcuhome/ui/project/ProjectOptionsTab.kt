// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import org.mcuhome.ui.api.ProjectOption
import org.mcuhome.ui.component.SurfaceCard
import org.mcuhome.ui.component.TableHeaderRow
import org.mcuhome.ui.component.TextAction
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.theme.MCUHomeTheme

/** The smallest height of a row; a two-line description makes it taller. */
private val OptionRowHeight = 60.dp

/** The two right-hand columns are labels, not content, and keep a fixed width. */
private val SetByWidth = 110.dp
private val ActionWidth = 90.dp

/** The two content columns share the rest of the row as weights. */
private const val OPTION_COLUMN_WEIGHT = 0.26f
private const val VALUE_COLUMN_WEIGHT = 0.48f

/**
 * Every option the builder resolves, with the value in force and the
 * layer that supplied it.
 *
 * The layer is what makes the last column mean anything: this screen
 * writes the project's own configuration file and nothing else, so it can
 * take back what it wrote and it can override what another layer says —
 * but it never edits another layer's file.
 */
@Composable
fun ProjectOptionsTab(
    options: List<ProjectOption>,
    onSet: (ProjectOption, String) -> Unit,
    onReset: (ProjectOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    SurfaceCard(modifier) {
        TableHeaderRow {
            HeaderLabel("Option", Modifier.weight(OPTION_COLUMN_WEIGHT))
            HeaderLabel("Value", Modifier.weight(VALUE_COLUMN_WEIGHT))
            HeaderLabel("Set by", Modifier.width(SetByWidth))
            Box(Modifier.width(ActionWidth))
        }
        options.forEachIndexed { index, option ->
            OptionRow(
                option = option,
                onSet = { value -> onSet(option, value) },
                onReset = { onReset(option) },
                last = index == options.lastIndex,
            )
        }
    }
}

@Composable
private fun HeaderLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 12.5.sp,
        modifier = modifier,
    )
}

@Composable
private fun OptionRow(
    option: ProjectOption,
    onSet: (String) -> Unit,
    onReset: () -> Unit,
    last: Boolean,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = OptionRowHeight)
            .then(if (last) Modifier else Modifier.bottomBorder())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(OPTION_COLUMN_WEIGHT).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = option.label,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Text(
                text = option.help,
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        OptionValueField(
            option = option,
            onSet = onSet,
            modifier = Modifier.weight(VALUE_COLUMN_WEIGHT).padding(end = 12.dp),
        )
        Text(
            text = originLabel(option),
            color = if (isProjectValue(option)) colors.accentOnTint else colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontWeight = if (isProjectValue(option)) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
            modifier = Modifier.width(SetByWidth),
        )
        Box(Modifier.width(ActionWidth), contentAlignment = Alignment.CenterStart) {
            when (optionAction(option)) {
                OptionAction.Reset -> TextAction(text = OptionAction.Reset.label, onClick = onReset)

                OptionAction.Override -> TextAction(
                    text = OptionAction.Override.label,
                    onClick = { option.value?.let(onSet) },
                )

                OptionAction.None -> Unit
            }
        }
    }
}
