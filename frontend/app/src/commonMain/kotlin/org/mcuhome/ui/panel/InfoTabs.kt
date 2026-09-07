// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.panel

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.Availability
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.NotAvailableNotice
import org.mcuhome.ui.component.ThinHorizontalScrollbar
import org.mcuhome.ui.component.ThinVerticalScrollbar
import org.mcuhome.ui.component.formatByteSize
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The live log of the running device — once anything can read one. Until
 * then the tab says why it cannot, in the server's own words, instead of
 * showing an empty console that never fills.
 */
@Composable
fun DeviceLogTab(notAvailable: Availability.NotAvailable?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (notAvailable != null) {
            NotAvailableNotice(notAvailable, Modifier.fillMaxWidth())
        }
        Text(
            text = "A device log needs a serial connection to a running board. " +
                "The transport, the pause and the save the design shows appear here with it.",
            color = MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.5.sp,
        )
    }
}

/** The canonical model the configuration resolves to, as the builder writes it. */
@Composable
fun ModelTab(state: ModelState, modifier: Modifier = Modifier) {
    when (state) {
        ModelState.Loading -> Box(modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Resolving…",
                color = MCUHomeTheme.colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
        }

        is ModelState.Refused -> Box(modifier.fillMaxSize().padding(12.dp)) {
            ErrorNotice(state.error, Modifier.fillMaxWidth())
        }

        is ModelState.Ready -> {
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            Box(modifier.fillMaxSize()) {
                Text(
                    text = state.model.json,
                    color = MCUHomeTheme.colors.ink,
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .verticalScroll(vertical)
                        .horizontalScroll(horizontal)
                        .padding(12.dp),
                )
                ThinVerticalScrollbar(vertical, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                ThinHorizontalScrollbar(horizontal, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

/** The files the last good build left behind, each with the way to fetch it. */
@Composable
fun ArtifactsTab(
    artifacts: List<ArtifactInfo>,
    onDownload: (ArtifactInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artifacts.isEmpty()) {
        Box(modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "This device has no build output. A finished build puts its files here.",
                color = MCUHomeTheme.colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            items(artifacts) { artifact -> ArtifactRow(artifact, onDownload) }
        }
        ThinVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun ArtifactRow(artifact: ArtifactInfo, onDownload: (ArtifactInfo) -> Unit) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = artifact.fileName,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.mono,
            fontSize = 12.5.sp,
        )
        Text(
            text = artifact.role,
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.sp,
        )
        Box(Modifier.weight(1f))
        Text(
            text = formatByteSize(artifact.sizeBytes),
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.sp,
        )
        MCUHomeIconButton(
            icon = MCUHomeIcons.download,
            contentDescription = "Download ${artifact.fileName}",
            onClick = { onDownload(artifact) },
            bordered = true,
        )
    }
}
