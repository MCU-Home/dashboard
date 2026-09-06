// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One brand color role, with the value it takes in the light scheme and
 * the value it takes in the dark scheme — the Kotlin equivalent of the
 * `light-dark(...)` pair the brand stylesheet defines for every custom
 * property.
 */
@Immutable
data class ColorToken(val light: Color, val dark: Color) {
    fun resolve(darkScheme: Boolean): Color = if (darkScheme) dark else light
}

/**
 * Every color the interface may use. The values mirror the MCUHome brand
 * stylesheet (`style.css` in the mcuhome-brand repository, custom
 * properties `--mcuhome-*`) one for one; this object is the only place a
 * color literal appears, so a change to the brand is a change to this
 * table and nowhere else.
 *
 * The block at the end holds the two roles the design uses that the brand
 * stylesheet does not define yet. They are kept apart deliberately: when
 * the brand gains the matching custom properties, the values move up into
 * the table above and the block disappears.
 */
object BrandColorTokens {
    // Core
    val accent = ColorToken(Color(0xFFD95513), Color(0xFFEF7B33))
    val accentStrong = ColorToken(Color(0xFFA8420F), Color(0xFFF49A5E))
    val ink = ColorToken(Color(0xFF221C18), Color(0xFFF4EFE9))
    val muted = ColorToken(Color(0xFF6F6659), Color(0xFFA89D90))
    val border = ColorToken(Color(0xFFE8E1D6), Color(0xFF3D342C))
    val background = ColorToken(Color(0xFFFDFBF8), Color(0xFF1D1815))
    val surface = ColorToken(Color(0xFFFFFFFF), Color(0xFF292219))
    val backgroundAlt = ColorToken(Color(0xFFF4EFE9), Color(0xFF231D17))
    val pinGray = ColorToken(Color(0xFFB0A89E), Color(0xFF8A8078))

    // Semantic: success
    val success = ColorToken(Color(0xFF3A7D44), Color(0xFF5CAB6B))
    val successTint = ColorToken(Color(0xFFE9F2EA), Color(0xFF292219))
    val successTintBorder = ColorToken(Color(0xFFC5DCC9), Color(0xFF5CAB6B))
    val successOnTint = ColorToken(Color(0xFF2D6336), Color(0xFF5CAB6B))

    // Semantic: info
    val info = ColorToken(Color(0xFF2E6CA4), Color(0xFF6AA5D8))
    val infoTint = ColorToken(Color(0xFFE7EFF6), Color(0xFF292219))
    val infoTintBorder = ColorToken(Color(0xFFC2D6E6), Color(0xFF6AA5D8))
    val infoOnTint = ColorToken(Color(0xFF26597F), Color(0xFF6AA5D8))

    // Semantic: warning
    val warning = ColorToken(Color(0xFFB07D0A), Color(0xFFD99A1E))
    val warningTint = ColorToken(Color(0xFFF7F0DA), Color(0xFF292219))
    val warningTintBorder = ColorToken(Color(0xFFE2D3A8), Color(0xFFD99A1E))
    val warningOnTint = ColorToken(Color(0xFF8A6106), Color(0xFFD99A1E))

    // Semantic: error
    val error = ColorToken(Color(0xFFB3332B), Color(0xFFDD6A5F))
    val errorTint = ColorToken(Color(0xFFF7E9E7), Color(0xFF292219))
    val errorTintBorder = ColorToken(Color(0xFFE3C2BD), Color(0xFFDD6A5F))
    val errorOnTint = ColorToken(Color(0xFF8F2822), Color(0xFFDD6A5F))

    // Roles the design uses that the brand stylesheet does not define yet:
    // the accent tint behind the jobs chip and the "building" pill, and
    // the editor's current-line highlight.
    val accentTint = ColorToken(Color(0xFFFDF5EE), Color(0xFF33291F))
    val accentTintBorder = ColorToken(Color(0xFFF2C9AB), Color(0xFFEF7B33))
    val accentOnTint = ColorToken(Color(0xFFA8420F), Color(0xFFF49A5E))
    val editorCurrentLine = ColorToken(Color(0xFFFDF5EE), Color(0xFF33291F))
}
