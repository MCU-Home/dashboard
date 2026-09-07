// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mcuhome.ui.api.BuildState
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.Diagnostic
import org.mcuhome.ui.api.DiagnosticSeverity
import org.mcuhome.ui.api.SignedState
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.panel.BuildRun
import org.mcuhome.ui.theme.MCUHomeTheme

/** The strip is as tall as its pills need and no taller. */
private val StripHeight = 40.dp

/**
 * What the status rail says, on a phone, in one line.
 *
 * A 260 px column of headings and rows does not fit on a phone, and the
 * part of it that is looked at most often is four words: whether the
 * configuration checks out, what the build is doing, what the validator
 * found, and whether the image is signed. Those are the pills; the rail
 * itself is behind the button on the right, one tap away.
 */
@Composable
fun DeviceStatusStrip(
    detail: DeviceDetail,
    diagnostics: List<Diagnostic>,
    build: BuildRun,
    onOpenRail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    val summary = detail.summary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(StripHeight)
            .background(colors.surface)
            .bottomBorder()
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConfigStatusPill(summary.config)
            Pill(
                text = buildSectionState(build, summary.build),
                tone = buildPillTone(build, summary.build.state),
                dot = build.running || summary.build.state == BuildState.Building,
            )
            // The diagnostics pill is left out where it would only
            // repeat the config pill beside it: on this page both are
            // counted from the same list, and "1 warning · 1 warning"
            // says nothing twice.
            val findings = diagnosticsSectionState(diagnostics)
            if (diagnostics.isNotEmpty() && findings != configSectionState(summary.config)) {
                Pill(text = findings, tone = diagnosticsPillTone(diagnostics))
            }
            if (summary.signed != SignedState.Unknown) {
                SignedStatePill(summary.signed)
            }
        }
        MCUHomeIconButton(
            icon = MCUHomeIcons.sidebar,
            contentDescription = "Show everything known about this device",
            onClick = onOpenRail,
            tint = colors.muted,
            size = 36.dp,
        )
    }
}

/** The colour of the build pill: what is happening, or what happened last. */
private fun buildPillTone(build: BuildRun, state: BuildState): PillTone = when {
    build.running || state == BuildState.Building -> PillTone.Accent
    state == BuildState.Failed -> PillTone.Error
    state == BuildState.Built -> PillTone.Success
    else -> PillTone.Neutral
}

/** The colour of the diagnostics pill: the worst thing in the list. */
private fun diagnosticsPillTone(diagnostics: List<Diagnostic>): PillTone = when {
    diagnostics.any { it.severity == DiagnosticSeverity.Error } -> PillTone.Error
    diagnostics.any { it.severity == DiagnosticSeverity.Warning } -> PillTone.Warning
    else -> PillTone.Info
}
