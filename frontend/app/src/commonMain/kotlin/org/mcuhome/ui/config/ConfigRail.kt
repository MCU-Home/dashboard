// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mcuhome.ui.api.ConfigStatus
import org.mcuhome.ui.api.ConfigUsersReport
import org.mcuhome.ui.api.SharedConfigFile
import org.mcuhome.ui.component.KeyValueRow
import org.mcuhome.ui.component.Pill
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.RailSection
import org.mcuhome.ui.component.RailValue
import org.mcuhome.ui.component.SideRail
import org.mcuhome.ui.device.ConfigStatusPill
import org.mcuhome.ui.panel.DiagnosticNotice
import org.mcuhome.ui.time.formatTimestamp

/** The width the rail of the shared-configuration screen shares with the device page. */
val ConfigRailWidth = 260.dp

/** What the rail is given on a tablet, where the editor beside it needs the difference. */
val NarrowConfigRailWidth = 210.dp

/**
 * What is known about the open fragment: who pulls it in and how those
 * devices fare, which secrets it names, and where the file is.
 *
 * The findings of "Validate users" belong here rather than in the
 * editor's gutter: they are about the devices that include this file,
 * and their line numbers point into *their* configuration, not into this
 * one. Showing them beside the text would put a marker on a line that has
 * nothing to do with the problem.
 */
@Composable
fun ConfigStatusRail(
    file: SharedConfigFile,
    userStatus: Map<String, ConfigStatus>,
    report: ConfigUsersReport?,
    nowEpochMillis: Long,
    modifier: Modifier = Modifier,
    width: Dp = ConfigRailWidth,
) {
    SideRail(width = width, modifier = modifier) {
        RailSection(title = "Used by") {
            if (file.summary.usedByDevices.isEmpty()) {
                RailValue("No device includes this file.")
            }
            file.summary.usedByDevices.forEach { device ->
                KeyValueRow(device) {
                    val status = userStatus[device]
                    if (status == null) RailValue("—") else ConfigStatusPill(status)
                }
            }
        }
        report?.users.orEmpty().forEach { user ->
            if (user.report.diagnostics.isEmpty()) return@forEach
            RailSection(title = "${user.device} · findings") {
                user.report.diagnostics.forEach { diagnostic ->
                    DiagnosticNotice(
                        diagnostic = diagnostic,
                        onJumpToLine = null,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    )
                }
            }
        }
        RailSection(title = "Secrets referenced") {
            if (file.referencedSecrets.isEmpty()) {
                RailValue("This file names no secret.")
            }
            file.referencedSecrets.forEach { reference ->
                KeyValueRow(label = reference.key, mono = true) {
                    if (reference.set) {
                        Pill(text = "set", tone = PillTone.Success)
                    } else {
                        Pill(text = "unset", tone = PillTone.Error)
                    }
                }
            }
        }
        RailSection(title = "File") {
            KeyValueRow("Path") { RailValue(file.summary.path, mono = true) }
            KeyValueRow("Changed") {
                RailValue(formatTimestamp(file.summary.changedAtEpochMillis, nowEpochMillis))
            }
        }
    }
}
