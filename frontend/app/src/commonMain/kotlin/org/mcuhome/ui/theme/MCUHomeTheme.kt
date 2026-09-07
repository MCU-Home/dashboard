// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

private val LocalMCUHomeColors = staticCompositionLocalOf { MCUHomeColors(darkScheme = false) }

private val LocalMCUHomeTypography = staticCompositionLocalOf {
    MCUHomeTypography(
        heading = FontFamily.SansSerif,
        body = FontFamily.SansSerif,
        mono = FontFamily.Monospace,
    )
}

/** The brand colors and type roles in scope, for any composable below [MCUHomeTheme]. */
object MCUHomeTheme {
    val colors: MCUHomeColors
        @Composable @ReadOnlyComposable
        get() = LocalMCUHomeColors.current

    val typography: MCUHomeTypography
        @Composable @ReadOnlyComposable
        get() = LocalMCUHomeTypography.current
}

/**
 * Puts the brand tokens in scope and follows the operating system's color
 * scheme — there is no in-app toggle, matching the brand stylesheet,
 * which resolves every token with `light-dark()` and lets `color-scheme`
 * decide.
 *
 * The Material color scheme underneath is derived from the same tokens so
 * that Material components (dialogs, text fields, ripples) never
 * introduce a color of their own.
 */
@Composable
fun MCUHomeTheme(darkScheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = remember(darkScheme) { MCUHomeColors(darkScheme) }
    val typography = rememberMCUHomeTypography()
    CompositionLocalProvider(
        LocalMCUHomeColors provides colors,
        LocalMCUHomeTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme(colors),
            typography = materialTypography(typography),
            content = content,
        )
    }
}

/**
 * Draws its content in the dark scheme whatever scheme the window is in.
 *
 * The design gives the output panel a dark surface in both schemes, so
 * that build output and a device log read like a terminal rather than
 * like a document. Rather than let that one area name colors of its own,
 * it is given the dark half of every brand token pair — the same table,
 * resolved the other way.
 */
@Composable
fun DarkSchemeContent(content: @Composable () -> Unit) {
    val colors = remember { MCUHomeColors(darkScheme = true) }
    CompositionLocalProvider(LocalMCUHomeColors provides colors) {
        MaterialTheme(
            colorScheme = materialColorScheme(colors),
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}

/**
 * Material's palette, filled from the brand tokens.
 *
 * Material draws dialogs, menus and sheets on the "container" surfaces
 * and tints them by elevation. The brand has one surface, so every
 * container is that surface and the tint is switched off.
 */
private fun materialColorScheme(colors: MCUHomeColors): ColorScheme {
    val base = if (colors.darkScheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.accent,
        onPrimary = colors.surface,
        secondary = colors.accentStrong,
        onSecondary = colors.surface,
        background = colors.background,
        onBackground = colors.ink,
        surface = colors.surface,
        onSurface = colors.ink,
        surfaceVariant = colors.backgroundAlt,
        onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.surface,
        surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.surface,
        surfaceTint = colors.surface,
        inverseSurface = colors.ink,
        inverseOnSurface = colors.surface,
        outline = colors.border,
        outlineVariant = colors.border,
        error = colors.error,
        onError = colors.surface,
        errorContainer = colors.errorTint,
        onErrorContainer = colors.errorOnTint,
    )
}

private fun materialTypography(typography: MCUHomeTypography): Typography = with(Typography()) {
    copy(
        displayLarge = displayLarge.copy(fontFamily = typography.heading),
        displayMedium = displayMedium.copy(fontFamily = typography.heading),
        displaySmall = displaySmall.copy(fontFamily = typography.heading),
        headlineLarge = headlineLarge.copy(fontFamily = typography.heading),
        headlineMedium = headlineMedium.copy(fontFamily = typography.heading),
        headlineSmall = headlineSmall.copy(fontFamily = typography.heading),
        titleLarge = titleLarge.copy(fontFamily = typography.heading),
        titleMedium = titleMedium.copy(fontFamily = typography.heading),
        titleSmall = titleSmall.copy(fontFamily = typography.heading),
        bodyLarge = bodyLarge.copy(fontFamily = typography.body),
        bodyMedium = bodyMedium.copy(fontFamily = typography.body),
        bodySmall = bodySmall.copy(fontFamily = typography.body),
        labelLarge = labelLarge.copy(fontFamily = typography.body),
        labelMedium = labelMedium.copy(fontFamily = typography.body),
        labelSmall = labelSmall.copy(fontFamily = typography.body),
    )
}
