// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.component.KeyValueRow
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.RailSection
import org.mcuhome.ui.component.RailValue
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.Tooltip
import org.mcuhome.ui.panel.BuildRun
import org.mcuhome.ui.panel.DiagnosticNotice
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.formatTimestamp

/** Whether the configuration checks out, what it pulls in, and how many secrets it resolves. */
@Composable
fun ConfigSection(
    detail: DeviceDetail,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
) {
    RailSection(
        title = "Config",
        modifier = modifier,
        action = {
            // A rail that came up as a sheet is closed by touching the
            // page above it; there is no column beside the editor for a
            // collapse button to collapse.
            if (collapsible) {
                Tooltip(text = "Collapse") {
                    MCUHomeIconButton(
                        icon = MCUHomeIcons.collapseRight,
                        contentDescription = "Collapse the status rail",
                        onClick = onCollapse,
                    )
                }
            }
        },
    ) {
        KeyValueRow("Validation") { ConfigStatusPill(detail.summary.config) }
        KeyValueRow("Includes") {
            RailValue(detail.includes.joinToString(", ").ifEmpty { "none" }, mono = detail.includes.isNotEmpty())
        }
        KeyValueRow("Secrets") { RailValue("${detail.resolvedSecretCount} resolved") }
    }
}

/** What the last build did, and what the running one is doing. */
@Composable
fun BuildSection(
    detail: DeviceDetail,
    build: BuildRun,
    nowEpochMillis: Long,
    modifier: Modifier = Modifier,
) {
    val summary = detail.summary
    val snapshot = build.snapshot
    RailSection(title = "Build", modifier = modifier) {
        KeyValueRow("State") {
            when {
                build.running || summary.build.state == BuildState.Building ->
                    Pill(text = buildRunningLabel(build), tone = PillTone.Accent, dot = true)

                summary.build.state == BuildState.Built -> Pill(text = "built", tone = PillTone.Success)

                summary.build.state == BuildState.Failed -> Pill(text = "failed", tone = PillTone.Error)

                else -> Pill(text = "never built", tone = PillTone.Neutral)
            }
        }
        val method = snapshot?.let { listOfNotNull(it.method.name.lowercase(), it.mode) }
            ?: detail.lastGoodBuild?.let { listOfNotNull(it.method.name.lowercase(), it.mode) }
            ?: summary.build.method?.let { listOf(it.name.lowercase()) }
        KeyValueRow("Method") { RailValue(method?.joinToString(" · ") ?: "—") }
        KeyValueRow("Last good") {
            val finished = detail.lastGoodBuild?.finishedAtEpochMillis
            RailValue(finished?.let { formatTimestamp(it, nowEpochMillis) } ?: "—")
        }
        KeyValueRow("Signed") { SignedStatePill(summary.signed, unknownLabel = "not yet") }
    }
}

/** The files of the last build that produced any, each with a way to fetch it. */
@Composable
fun ArtifactsSection(
    artifacts: List<ArtifactInfo>,
    onDownload: (ArtifactInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artifacts.isEmpty()) return
    RailSection(title = "Artifacts · last good build", modifier = modifier) {
        artifacts.forEach { artifact ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = artifact.fileName,
                    color = MCUHomeTheme.colors.ink,
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
                MCUHomeIconButton(
                    icon = MCUHomeIcons.download,
                    contentDescription = "Download ${artifact.fileName}",
                    onClick = { onDownload(artifact) },
                    bordered = true,
                )
            }
        }
    }
}

/** Whether the device can be commissioned, and the way to the codes. */
@Composable
fun PairingSection(
    detail: DeviceDetail,
    onShowPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pairing = detail.pairing
    RailSection(title = "Matter pairing", modifier = modifier) {
        KeyValueRow("Credentials") { RailValue(if (pairing?.present == true) "present" else "none yet") }
        if (pairing?.present == true) {
            KeyValueRow("Discriminator") { RailValue(pairing.maskedDiscriminator) }
        }
        SecondaryButton(
            text = if (pairing?.present == true) "Show QR code" else "Draw credentials…",
            onClick = onShowPairing,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** Everything the validator found, in the order it found it. */
@Composable
fun DiagnosticsSection(
    diagnostics: List<Diagnostic>,
    onJumpToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    RailSection(title = "Diagnostics", modifier = modifier) {
        if (diagnostics.isEmpty()) {
            RailValue("Nothing to report.")
            return@RailSection
        }
        diagnostics.forEach { diagnostic ->
            DiagnosticNotice(diagnostic, onJumpToLine, Modifier.fillMaxWidth().padding(vertical = 3.dp))
        }
    }
}
