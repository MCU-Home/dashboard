// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

/**
 * What a device may be called.
 *
 * The name becomes the device's folder under `devices/` and the host name
 * it announces itself with, so it is restricted to what both accept:
 * lowercase letters, digits and hyphens, starting with a letter. The
 * check is the same one the server applies — it runs here as well so the
 * dialog can say no before it asks.
 */
private val DEVICE_NAME = Regex("^[a-z][a-z0-9-]*$")

/** The rule, in the words the New device dialog puts under the field. */
const val DEVICE_NAME_RULE: String = "Lowercase, digits, hyphens."

fun isValidDeviceName(name: String): Boolean = DEVICE_NAME.matches(name)

/** A friendly name derived from the device name, for a dialog that was not given one. */
fun friendlyNameFor(name: String): String =
    name.split('-').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
