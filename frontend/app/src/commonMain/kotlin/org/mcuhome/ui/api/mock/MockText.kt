// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

// The three things the mock reads out of a configuration's text. They are
// derived rather than declared on purpose: the includes a device has, the
// secrets it refers to and the used-by columns of the Secrets and Configs
// screens all follow from the files, so editing a file in the interface
// changes them.

package org.mcuhome.ui.api.mock

private const val MAX_MASK_LENGTH = 16
private val INCLUDE = Regex("""!include\s+(\S+)""")
private val SECRET = Regex("""!secret\s+([A-Za-z0-9_.\-]+)""")

/** The files a configuration pulls in with `!include`, by file name. */
internal fun includedFiles(text: String): List<String> =
    INCLUDE.findAll(text).map { it.groupValues[1].substringAfterLast('/') }.distinct().toList()

/** The secret keys a configuration refers to. */
internal fun referencedSecrets(text: String): List<String> =
    SECRET.findAll(text).map { it.groupValues[1] }.distinct().toList()

/** A value as the interface shows it before anybody asks to see it. */
internal fun mask(value: String): String = "•".repeat(value.length.coerceAtMost(MAX_MASK_LENGTH))
