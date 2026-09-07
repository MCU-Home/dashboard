// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

private const val UNIT = 1024.0
private const val ONE_DECIMAL_BELOW = 10.0
private const val TENTHS = 10

/**
 * A file's size as a list of artifacts prints it: "412 KB", "1.2 MB",
 * "980 B".
 *
 * Sizes are scaled by 1024, the way a firmware image is measured
 * everywhere else in this project, and are given one decimal only while
 * that decimal still says something — below ten of a unit.
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < UNIT) return "$bytes B"
    var value = bytes / UNIT
    val units = listOf("KB", "MB", "GB")
    var index = 0
    while (value >= UNIT && index < units.lastIndex) {
        value /= UNIT
        index++
    }
    return if (value < ONE_DECIMAL_BELOW) {
        val tenths = (value * TENTHS).toLong()
        "${tenths / TENTHS}.${tenths % TENTHS} ${units[index]}"
    } else {
        "${value.toLong()} ${units[index]}"
    }
}
