// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.qr

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * A payload as the square a phone can read.
 *
 * The code is really encoded rather than drawn as decoration — a
 * commissioning code that cannot be scanned is worse than none, because
 * it looks like it works. The two colors come from the brand tokens like
 * everything else; a QR code only needs the contrast between them, and
 * ink on surface has more than enough.
 */
@Composable
fun QrCodeImage(
    payload: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val darkColor = MCUHomeTheme.colors.ink
    val lightColor = MCUHomeTheme.colors.surface
    val painter = rememberQrCodePainter(payload) {
        colors {
            dark = QrBrush.solid(darkColor)
            light = QrBrush.solid(lightColor)
        }
    }
    Image(painter = painter, contentDescription = contentDescription, modifier = modifier)
}
