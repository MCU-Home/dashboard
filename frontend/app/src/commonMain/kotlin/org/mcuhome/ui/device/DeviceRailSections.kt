// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.ArtifactInfo
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.panel.BuildRun
import org.mcuhome.ui.panel.DiagnosticNotice
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.formatTimestamp

/** Whether the configuration checks out, what it pulls in, and how many secrets it resolves. */
@Composable
fun ConfigSection(detail: DeviceDetail, onCollapse: () -> Unit) {
    RailSection(
        title = "Config",
        action = {
            MCUHomeIconButton(
                icon = MCUHomeIcons.dockRight,
                contentDescription = "Collapse the status rail",
                onClick = onCollapse,
            )
        },
    ) {
        RailRow("Validation") { ConfigStatusPill(detail.summary.config) }
        RailRow("Includes") {
            RailValue(detail.includes.joinToString(", ").ifEmpty { "none" }, mono = detail.includes.isNotEmpty())
        }
        RailRow("Secrets") { RailValue("${detail.resolvedSecretCount} resolved") }
    }
}

/** What the last build did, and what the running one is doing. */
@Composable
fun BuildSection(
    detail: DeviceDetail,
    build: BuildRun,
    nowEpochMillis: Long,
) {
    val summary = detail.summary
    val snapshot = build.snapshot
    RailSection(title = "Build") {
        RailRow("State") {
            when {
                build.running || summary.build.state == BuildState.Building ->
                    Pill(text = runningLabel(build), tone = PillTone.Accent, dot = true)

                summary.build.state == BuildState.Built -> Pill(text = "built", tone = PillTone.Success)

                summary.build.state == BuildState.Failed -> Pill(text = "failed", tone = PillTone.Error)

                else -> Pill(text = "never built", tone = PillTone.Neutral)
            }
        }
        val method = snapshot?.let { listOfNotNull(it.method.name.lowercase(), it.mode) }
            ?: detail.lastGoodBuild?.let { listOfNotNull(it.method.name.lowercase(), it.mode) }
            ?: summary.build.method?.let { listOf(it.name.lowercase()) }
        RailRow("Method") { RailValue(method?.joinToString(" · ") ?: "—") }
        RailRow("Last good") {
            val finished = detail.lastGoodBuild?.finishedAtEpochMillis
            RailValue(finished?.let { formatTimestamp(it, nowEpochMillis) } ?: "—")
        }
        RailRow("Signed") { SignedStatePill(summary.signed, unknownLabel = "not yet") }
    }
}

/** The files of the last build that produced any, each with a way to fetch it. */
@Composable
fun ArtifactsSection(artifacts: List<ArtifactInfo>, onDownload: (ArtifactInfo) -> Unit) {
    if (artifacts.isEmpty()) return
    RailSection(title = "Artifacts · last good build") {
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
fun PairingSection(detail: DeviceDetail, onShowPairing: () -> Unit) {
    val pairing = detail.pairing
    RailSection(title = "Matter pairing") {
        RailRow("Credentials") { RailValue(if (pairing?.present == true) "present" else "none yet") }
        if (pairing?.present == true) {
            RailRow("Discriminator") { RailValue(pairing.maskedDiscriminator) }
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
fun DiagnosticsSection(diagnostics: List<Diagnostic>, onJumpToLine: (Int) -> Unit) {
    RailSection(title = "Diagnostics") {
        if (diagnostics.isEmpty()) {
            RailValue("Nothing to report.")
            return@RailSection
        }
        diagnostics.forEach { diagnostic ->
            DiagnosticNotice(diagnostic, onJumpToLine, Modifier.fillMaxWidth().padding(vertical = 3.dp))
        }
    }
}

/** One block of the rail: a heading in small capitals and what it says. */
@Composable
private fun RailSection(
    title: String,
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = colors.border,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.uppercase(),
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
            )
            Box(Modifier.weight(1f))
            action()
        }
        content()
    }
}

/** One line of a section: what it is on the left, what it says on the right. */
@Composable
private fun RailRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MCUHomeTheme.colors.muted,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 12.5.sp,
        )
        Box(Modifier.weight(1f))
        value()
    }
}

@Composable
private fun RailValue(text: String, mono: Boolean = false) {
    Text(
        text = text,
        color = MCUHomeTheme.colors.ink,
        fontFamily = if (mono) MCUHomeTheme.typography.mono else MCUHomeTheme.typography.body,
        fontSize = 12.5.sp,
    )
}

/** The stage a running build is in, as the rail's pill says it: "compiling". */
private fun runningLabel(build: BuildRun): String = build.snapshot?.currentStage?.name?.lowercase()?.let { stage ->
    if (stage.endsWith("e")) "${stage.dropLast(1)}ing" else "${stage}ing"
} ?: "building"
