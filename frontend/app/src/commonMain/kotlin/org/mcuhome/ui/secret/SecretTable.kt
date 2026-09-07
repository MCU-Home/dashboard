// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.secret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.SecretEntry
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.RevealValue
import org.mcuhome.ui.component.SurfaceCard
import org.mcuhome.ui.component.TableHeaderCell
import org.mcuhome.ui.component.TableHeaderRow
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.theme.MCUHomeTheme

/** The height of one row; the three icon buttons need it and so does the pointer. */
private val SecretRowHeight = 47.dp

/** The width the three row actions take together. */
private val ActionColumnWidth = 116.dp

/** The columns share their widths as weights, so the table follows the window. */
private const val KEY_COLUMN_WEIGHT = 0.30f
private const val VALUE_COLUMN_WEIGHT = 0.44f
private const val USED_BY_COLUMN_WEIGHT = 0.26f

/** What a row of the secrets table can start. */
@Immutable
data class SecretRowActions(
    val onToggleReveal: (SecretEntry) -> Unit,
    val onEdit: (SecretEntry) -> Unit,
    val onDelete: (SecretEntry) -> Unit,
)

/**
 * The keys of one secrets file: what each is called, the dots that stand
 * in for its value, and where the value is actually used.
 *
 * The value column carries what the server sent — dots — until a reveal
 * request answers for exactly that key. Nothing here can unmask anything
 * on its own.
 */
@Composable
fun SecretTable(
    entries: List<SecretEntry>,
    revealed: RevealedSecrets,
    ascending: Boolean,
    onSort: () -> Unit,
    actions: SecretRowActions,
    modifier: Modifier = Modifier,
) {
    SurfaceCard(modifier) {
        TableHeaderRow {
            TableHeaderCell(
                label = "Key",
                ascending = ascending,
                onClick = onSort,
                modifier = Modifier.weight(KEY_COLUMN_WEIGHT),
            )
            HeaderLabel("Value", Modifier.weight(VALUE_COLUMN_WEIGHT))
            HeaderLabel("Used by", Modifier.weight(USED_BY_COLUMN_WEIGHT))
            Box(Modifier.width(ActionColumnWidth))
        }
        if (entries.isEmpty()) {
            Text(
                text = "This scope holds no secret yet.",
                color = MCUHomeTheme.colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        entries.forEachIndexed { index, entry ->
            SecretRow(
                entry = entry,
                revealed = revealed.revealed(entry.key),
                actions = actions,
                last = index == entries.lastIndex,
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
private fun SecretRow(
    entry: SecretEntry,
    revealed: String?,
    actions: SecretRowActions,
    last: Boolean,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SecretRowHeight)
            .then(if (last) Modifier else Modifier.bottomBorder())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.key,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.mono,
            fontSize = 13.sp,
            modifier = Modifier.weight(KEY_COLUMN_WEIGHT),
        )
        RevealValue(
            masked = entry.maskedValue,
            revealed = revealed,
            modifier = Modifier.weight(VALUE_COLUMN_WEIGHT),
        )
        Column(Modifier.weight(USED_BY_COLUMN_WEIGHT)) {
            if (entry.unused) {
                Pill(text = "unused", tone = PillTone.Neutral)
            } else {
                Text(
                    text = usedByLabel(entry),
                    color = colors.muted,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 13.sp,
                )
            }
        }
        Row(
            modifier = Modifier.width(ActionColumnWidth),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MCUHomeIconButton(
                icon = if (revealed == null) MCUHomeIcons.eye else MCUHomeIcons.eyeOff,
                contentDescription = if (revealed == null) "Show ${entry.key}" else "Hide ${entry.key}",
                onClick = { actions.onToggleReveal(entry) },
                bordered = true,
            )
            MCUHomeIconButton(
                icon = MCUHomeIcons.pencil,
                contentDescription = "Change ${entry.key}",
                onClick = { actions.onEdit(entry) },
                bordered = true,
            )
            MCUHomeIconButton(
                icon = MCUHomeIcons.trash,
                contentDescription = "Delete ${entry.key}",
                onClick = { actions.onDelete(entry) },
                bordered = true,
            )
        }
    }
}
