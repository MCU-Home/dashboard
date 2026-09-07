// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.PublicKey
import org.mcuhome.ui.component.KeyValueRow
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.RailValue
import org.mcuhome.ui.component.SurfaceCard
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The public half of the project's firmware signing key: where the key
 * file is, what it is, and the text a device or a colleague needs to
 * verify an image.
 *
 * Only the public half exists here. The private key never leaves the
 * project tree and nothing in the API can read it, which is why the card
 * says so rather than offering a button that would have to refuse.
 */
@Composable
fun PublicKeyCard(key: PublicKey, modifier: Modifier = Modifier) {
    val colors = MCUHomeTheme.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    SurfaceCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Firmware signing key",
                    color = colors.ink,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                MCUHomeIconButton(
                    icon = MCUHomeIcons.copy,
                    contentDescription = "Copy the public key",
                    onClick = { scope.launch { clipboard.setClipEntry(ClipEntry.withPlainText(key.pem)) } },
                    bordered = true,
                )
            }
            KeyValueRow("Key file") { RailValue(key.keyFile, mono = true) }
            KeyValueRow("Algorithm") { RailValue(key.algorithm) }
            Text(
                text = key.pem,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.mono,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.backgroundAlt)
                    .padding(10.dp),
            )
            Text(
                text = "The private half stays in the project and is never sent here.",
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.sp,
            )
        }
    }
}
