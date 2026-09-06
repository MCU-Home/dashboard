// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import org.mcuhome.ui.resource.Res
import org.mcuhome.ui.resource.sora_bold
import org.mcuhome.ui.resource.sora_regular
import org.mcuhome.ui.resource.sora_semibold

/**
 * The three type roles the brand defines: Sora for the wordmark and
 * headings, the platform's sans-serif for body text, the platform's
 * monospace for YAML and log output. Sora is bundled because the
 * rendering surface has no access to installed system fonts and the
 * interface must not depend on a font server being reachable.
 */
@Immutable
data class MCUHomeTypography(
    val heading: FontFamily,
    val body: FontFamily,
    val mono: FontFamily,
)

@Composable
fun rememberMCUHomeTypography(): MCUHomeTypography = MCUHomeTypography(
    heading = FontFamily(
        Font(Res.font.sora_regular, FontWeight.Normal),
        Font(Res.font.sora_semibold, FontWeight.SemiBold),
        Font(Res.font.sora_bold, FontWeight.Bold),
    ),
    body = FontFamily.SansSerif,
    mono = FontFamily.Monospace,
)
