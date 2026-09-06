// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The brand colors resolved for one color scheme. Composables read them
 * through `MCUHomeTheme.colors` and never touch [BrandColorTokens]
 * directly, so switching the scheme is a single value change at the root
 * of the tree.
 */
@Immutable
class MCUHomeColors(val darkScheme: Boolean) {
    val accent: Color get() = BrandColorTokens.accent.resolve(darkScheme)
    val accentStrong: Color get() = BrandColorTokens.accentStrong.resolve(darkScheme)
    val ink: Color get() = BrandColorTokens.ink.resolve(darkScheme)
    val muted: Color get() = BrandColorTokens.muted.resolve(darkScheme)
    val border: Color get() = BrandColorTokens.border.resolve(darkScheme)
    val background: Color get() = BrandColorTokens.background.resolve(darkScheme)
    val surface: Color get() = BrandColorTokens.surface.resolve(darkScheme)
    val backgroundAlt: Color get() = BrandColorTokens.backgroundAlt.resolve(darkScheme)
    val pinGray: Color get() = BrandColorTokens.pinGray.resolve(darkScheme)

    val success: Color get() = BrandColorTokens.success.resolve(darkScheme)
    val successTint: Color get() = BrandColorTokens.successTint.resolve(darkScheme)
    val successTintBorder: Color get() = BrandColorTokens.successTintBorder.resolve(darkScheme)
    val successOnTint: Color get() = BrandColorTokens.successOnTint.resolve(darkScheme)

    val info: Color get() = BrandColorTokens.info.resolve(darkScheme)
    val infoTint: Color get() = BrandColorTokens.infoTint.resolve(darkScheme)
    val infoTintBorder: Color get() = BrandColorTokens.infoTintBorder.resolve(darkScheme)
    val infoOnTint: Color get() = BrandColorTokens.infoOnTint.resolve(darkScheme)

    val warning: Color get() = BrandColorTokens.warning.resolve(darkScheme)
    val warningTint: Color get() = BrandColorTokens.warningTint.resolve(darkScheme)
    val warningTintBorder: Color get() = BrandColorTokens.warningTintBorder.resolve(darkScheme)
    val warningOnTint: Color get() = BrandColorTokens.warningOnTint.resolve(darkScheme)

    val error: Color get() = BrandColorTokens.error.resolve(darkScheme)
    val errorTint: Color get() = BrandColorTokens.errorTint.resolve(darkScheme)
    val errorTintBorder: Color get() = BrandColorTokens.errorTintBorder.resolve(darkScheme)
    val errorOnTint: Color get() = BrandColorTokens.errorOnTint.resolve(darkScheme)

    val accentTint: Color get() = BrandColorTokens.accentTint.resolve(darkScheme)
    val accentTintBorder: Color get() = BrandColorTokens.accentTintBorder.resolve(darkScheme)
    val accentOnTint: Color get() = BrandColorTokens.accentOnTint.resolve(darkScheme)

    /**
     * The editor's syntax roles. The design assigns them to existing brand
     * roles rather than to colors of their own, so they are derived here
     * instead of adding entries to the token table.
     */
    val editorKey: Color get() = ink
    val editorValue: Color get() = info
    val editorTag: Color get() = warning
    val editorLiteral: Color get() = success
    val editorComment: Color get() = muted
    val editorGutter: Color get() = muted
    val editorCurrentLine: Color get() = BrandColorTokens.editorCurrentLine.resolve(darkScheme)
}
