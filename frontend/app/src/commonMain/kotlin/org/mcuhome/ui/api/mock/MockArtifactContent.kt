// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import org.mcuhome.ui.api.ArtifactInfo

/**
 * What the mock hands out when a screen asks where an artifact's bytes
 * are.
 *
 * There is no server behind the mock, so there is no route to fetch from.
 * Instead of answering with a URL that would fail, it answers with the
 * bytes themselves — a `data:` URL carrying a note that says what the
 * file would have been. The download path in the browser is therefore
 * exercised end to end, and the real back end simply answers with its own
 * `/api/build/{build}/artifact/{path}` route instead.
 */
internal fun mockArtifactUrl(artifact: ArtifactInfo): String = "data:text/plain;charset=utf-8," +
    percentEncode(mockArtifactText(artifact))

private fun mockArtifactText(artifact: ArtifactInfo): String = """
    MCUHome — placeholder for ${artifact.fileName}

    role:   ${artifact.role}
    sha256: ${artifact.sha256}
    size:   ${artifact.sizeBytes} bytes

    This interface is running against the in-memory mock, which has no
    build output to hand out. The real file comes from the build that
    produced it.
""".trimIndent()

private const val HEX = "0123456789ABCDEF"
private const val BYTE_MASK = 0xFF
private const val LOW_NIBBLE = 0x0F
private const val NIBBLE_BITS = 4
private val UNRESERVED = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')

/** Percent-encoding, so any text can travel inside a URL. */
private fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val char = byte.toInt().toChar()
        if (char in UNRESERVED) {
            append(char)
        } else {
            val value = byte.toInt() and BYTE_MASK
            append('%').append(HEX[value shr NIBBLE_BITS]).append(HEX[value and LOW_NIBBLE])
        }
    }
}
