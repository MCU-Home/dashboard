// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import androidx.compose.foundation.layout.Arrangement
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
import org.mcuhome.ui.api.DoctorCheck
import org.mcuhome.ui.api.DoctorReport
import org.mcuhome.ui.api.DoctorSection
import org.mcuhome.ui.api.DoctorStatus
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.SurfaceCard
import org.mcuhome.ui.component.TableHeaderRow
import org.mcuhome.ui.component.bottomBorder
import org.mcuhome.ui.theme.MCUHomeTheme

private val CheckRowHeight = 48.dp
private val StatusColumnWidth = 120.dp

/** The name and the message share the row as weights. */
private const val NAME_COLUMN_WEIGHT = 0.3f
private const val MESSAGE_COLUMN_WEIGHT = 0.7f

/**
 * What `mcuhome doctor` found, in the groups it reports.
 *
 * Every check states what it looked at, how it came out and — where
 * something is wrong — the command or the place that fixes it, in the
 * server's own words, so the interface and the command line answer the
 * same question the same way.
 */
@Composable
fun DoctorChecks(report: DoctorReport, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        report.sections.forEach { section -> DoctorSectionCard(section, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun DoctorSectionCard(section: DoctorSection, modifier: Modifier = Modifier) {
    SurfaceCard(modifier) {
        TableHeaderRow(Modifier.fillMaxWidth()) {
            Text(
                text = section.title,
                color = MCUHomeTheme.colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.5.sp,
            )
        }
        section.checks.forEachIndexed { index, check ->
            CheckRow(check, last = index == section.checks.lastIndex)
        }
    }
}

@Composable
private fun CheckRow(check: DoctorCheck, last: Boolean) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CheckRowHeight)
            .then(if (last) Modifier else Modifier.bottomBorder())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = check.name,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
            modifier = Modifier.weight(NAME_COLUMN_WEIGHT).padding(end = 12.dp),
        )
        Column(Modifier.weight(MESSAGE_COLUMN_WEIGHT).padding(end = 12.dp)) {
            Text(
                text = check.message,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
            check.hint?.let { hint ->
                Text(
                    text = hint,
                    color = colors.muted,
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 12.sp,
                )
            }
        }
        Row(Modifier.width(StatusColumnWidth)) { StatusPill(check.status) }
    }
}

@Composable
private fun StatusPill(status: DoctorStatus) {
    when (status) {
        DoctorStatus.Ok -> Pill(text = "ok", tone = PillTone.Success, icon = MCUHomeIcons.check)
        DoctorStatus.Warning -> Pill(text = "warning", tone = PillTone.Warning, icon = MCUHomeIcons.warningTriangle)
        DoctorStatus.Failed -> Pill(text = "failed", tone = PillTone.Error, icon = MCUHomeIcons.errorCircle)
        DoctorStatus.Skipped -> Pill(text = "skipped", tone = PillTone.Neutral)
    }
}
