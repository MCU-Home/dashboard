// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.Availability
import org.mcuhome.ui.theme.MCUHomeTheme

private val NoticeShape = RoundedCornerShape(8.dp)

/**
 * A short statement the interface makes about something that happened or
 * about something it cannot do: a refused command, a warning before an
 * action, a diagnostic in a list.
 *
 * The tone decides the three tokens it is drawn in and nothing else; the
 * words come from wherever the statement came from — most often the
 * server, whose wording is written for the person in front of the screen
 * and is repeated here rather than translated, so the interface and the
 * command line answer the same question the same way.
 */
@Composable
fun Notice(
    tone: PillTone,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val notice = pillColors(tone)
    Row(
        modifier = modifier
            .clip(NoticeShape)
            .background(notice.fill)
            .border(width = 1.dp, color = notice.outline, shape = NoticeShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = notice.content,
                modifier = Modifier.size(16.dp).padding(top = 1.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    color = notice.content,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
            Text(
                text = message,
                color = notice.content,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = if (title == null) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = if (title == null) 13.sp else 12.5.sp,
            )
        }
        if (onDismiss != null) {
            MCUHomeIconButton(
                icon = MCUHomeIcons.close,
                contentDescription = "Dismiss this message",
                onClick = onDismiss,
                tint = notice.content,
            )
        }
    }
}

/** What the interface says when a command was refused. */
@Composable
fun ErrorNotice(
    error: ApiError,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Notice(
        tone = PillTone.Error,
        title = error.hint?.let { error.message },
        message = error.hint ?: error.message,
        icon = MCUHomeIcons.errorCircle,
        modifier = modifier,
        onDismiss = onDismiss,
    )
}

/**
 * What the interface says where a capability would be: the server's own
 * reason why it cannot do this yet, in the place the answer would have
 * gone, rather than a button that fails when it is pressed.
 */
@Composable
fun NotAvailableNotice(notAvailable: Availability.NotAvailable, modifier: Modifier = Modifier) {
    Notice(
        tone = PillTone.Warning,
        title = "Not available yet",
        message = listOfNotNull(notAvailable.reason, notAvailable.hint).joinToString(" — "),
        icon = MCUHomeIcons.warningTriangle,
        modifier = modifier,
    )
}
