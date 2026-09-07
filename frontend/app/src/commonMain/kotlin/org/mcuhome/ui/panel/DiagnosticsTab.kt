// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Notice
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.ThinVerticalScrollbar
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * Everything the last validation found, one notice per finding. Clicking
 * one puts the caret on the line it is about — a list of problems is only
 * useful if it leads to them.
 */
@Composable
fun DiagnosticsTab(
    diagnostics: List<Diagnostic>,
    onJumpToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (diagnostics.isEmpty()) {
        Box(modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "The configuration is valid — nothing to report.",
                color = MCUHomeTheme.colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(diagnostics) { diagnostic ->
                DiagnosticNotice(diagnostic, onJumpToLine, Modifier.fillMaxWidth())
            }
        }
        ThinVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * One finding: where it is, what it is about, and the fix the validator
 * suggested. The place is written the way an editor writes it, so it can
 * be recognised in the gutter beside the text.
 */
@Composable
fun DiagnosticNotice(
    diagnostic: Diagnostic,
    onJumpToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val place = diagnostic.line?.let { line -> "${diagnostic.file?.substringAfterLast('/').orEmpty()}:$line" }
    val title = listOfNotNull(diagnostic.key, place).joinToString(" · ").ifEmpty { null }
    Notice(
        tone = diagnostic.tone(),
        title = title,
        message = listOfNotNull(diagnostic.message, diagnostic.hint).joinToString(" "),
        icon = diagnostic.icon(),
        modifier = modifier,
        onClick = diagnostic.line?.let { line -> { onJumpToLine(line) } },
    )
}

private fun Diagnostic.tone(): PillTone = when (severity) {
    DiagnosticSeverity.Error -> PillTone.Error
    DiagnosticSeverity.Warning -> PillTone.Warning
    DiagnosticSeverity.Info -> PillTone.Info
}

private fun Diagnostic.icon() = when (severity) {
    DiagnosticSeverity.Error -> MCUHomeIcons.errorCircle
    DiagnosticSeverity.Warning -> MCUHomeIcons.warningTriangle
    DiagnosticSeverity.Info -> MCUHomeIcons.infoCircle
}
