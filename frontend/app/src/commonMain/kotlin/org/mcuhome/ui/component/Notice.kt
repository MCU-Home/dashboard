// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.theme.MCUHomeTheme

private val NoticeShape = RoundedCornerShape(8.dp)

/**
 * What the interface says when a command was refused: the server's own
 * sentence, and the fix it suggested where it suggested one.
 *
 * The wording comes from the server unchanged. It is written for the
 * person in front of the screen, and repeating it here rather than
 * translating it means the interface and the command line answer the same
 * question the same way.
 */
@Composable
fun ErrorNotice(
    error: ApiError,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = modifier
            .clip(NoticeShape)
            .background(colors.errorTint)
            .border(width = 1.dp, color = colors.errorTintBorder, shape = NoticeShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = MCUHomeIcons.errorCircle,
            contentDescription = null,
            tint = colors.errorOnTint,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = error.message,
                color = colors.errorOnTint,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            error.hint?.let { hint ->
                Text(
                    text = hint,
                    color = colors.errorOnTint,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 12.5.sp,
                )
            }
        }
        if (onDismiss != null) {
            MCUHomeIconButton(
                icon = MCUHomeIcons.close,
                contentDescription = "Dismiss this message",
                onClick = onDismiss,
                tint = colors.errorOnTint,
            )
        }
    }
}
