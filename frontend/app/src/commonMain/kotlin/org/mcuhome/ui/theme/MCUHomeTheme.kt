// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalMCUHomeColors = staticCompositionLocalOf { MCUHomeColors(darkScheme = false) }

private val LocalMCUHomeTypography = staticCompositionLocalOf {
    MCUHomeTypography(
        heading = androidx.compose.ui.text.font.FontFamily.SansSerif,
        body = androidx.compose.ui.text.font.FontFamily.SansSerif,
        mono = androidx.compose.ui.text.font.FontFamily.Monospace,
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

    val materialColors = if (darkScheme) {
        darkColorScheme(
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
            // Material draws dialogs, menus and sheets on the "container"
            // surfaces and tints them by elevation. The brand has one
            // surface, so every container is that surface and the tint is
            // switched off.
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
    } else {
        lightColorScheme(
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
            // Material draws dialogs, menus and sheets on the "container"
            // surfaces and tints them by elevation. The brand has one
            // surface, so every container is that surface and the tint is
            // switched off.
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

    val materialTypography = with(Typography()) {
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

    CompositionLocalProvider(
        LocalMCUHomeColors provides colors,
        LocalMCUHomeTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = materialTypography,
            content = content,
        )
    }
}
