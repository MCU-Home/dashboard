// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.config

import org.mcuhome.ui.api.ConfigUsersReport
import org.mcuhome.ui.api.SharedConfigSummary

/**
 * What the file list writes beside a file's name.
 *
 * A fragment nobody includes is worth pointing out — it is either the
 * start of something or the remains of something — so "unused" is a word
 * of its own rather than "0 devices".
 */
fun usedByLabel(deviceCount: Int): String = when (deviceCount) {
    0 -> "unused"
    1 -> "1 device"
    else -> "$deviceCount devices"
}

/**
 * The file the screen opens.
 *
 * The address bar names it (`#configs/<name>`); when it names none, or
 * names one that is no longer there, the first file of the list is
 * opened, and the address bar is corrected to say so.
 */
fun openedConfigFile(files: List<SharedConfigSummary>, requested: String?): String? =
    files.firstOrNull { it.fileName == requested }?.fileName ?: files.firstOrNull()?.fileName

/** The headline of the "Validate users" result: how many devices were checked, and how they fared. */
fun usersReportTitle(report: ConfigUsersReport): String = when {
    report.users.isEmpty() -> "No device includes this file"
    report.ok -> "Every device that uses this file validates"
    else -> "${report.users.count { !it.report.ok }} of ${report.users.size} devices report an error"
}

/** What the result says under its headline: the devices themselves, so the rail can be skipped. */
fun usersReportMessage(report: ConfigUsersReport): String = if (report.users.isEmpty()) {
    "Nothing was checked. A device includes a fragment with !include ../../configs/${report.fileName}."
} else {
    report.users.joinToString(", ") { it.device }
}
