// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.ApiError
import org.mcuhome.ui.api.ApiException
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.api.PairingCredentials
import org.mcuhome.ui.component.ErrorNotice
import org.mcuhome.ui.component.MCUHomeIconButton
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.ModalCard
import org.mcuhome.ui.component.Notice
import org.mcuhome.ui.component.PillTone
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.qr.QrCodeImage
import org.mcuhome.ui.theme.MCUHomeTheme

private val DialogWidth = 640.dp
private val QrCardSize = 172.dp

/** A passcode is eight digits; the dots stand in for all of them. */
private const val PASSCODE_MASK = "••••••••"

/**
 * The Matter commissioning credentials of one device: the square a phone
 * scans, the code a phone can be told instead, and the two numbers both
 * of them are made of.
 *
 * The passcode is masked until it is asked for. It travels in the clear —
 * the codes beside it are derived from it, so hiding it in transport
 * would protect nothing — but a screen is read by whoever is standing
 * next to it, and a screenshot outlives the moment.
 */
@Composable
fun PairingDialog(
    device: String,
    onDismiss: () -> Unit,
    onCredentialsChanged: () -> Unit = {},
) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var credentials by remember { mutableStateOf<PairingCredentials?>(null) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var confirmDraw by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(device) {
        try {
            credentials = api.pairing.get(device)
        } catch (failure: ApiException) {
            error = failure.error
        }
    }

    fun draw(force: Boolean) {
        error = null
        confirmDraw = false
        scope.launch {
            try {
                credentials = api.pairing.draw(device, force).credentials
                revealed = false
                // The file and the device's secrets have changed; the rail
                // beside the dialog is showing the state from before.
                onCredentialsChanged()
            } catch (failure: ApiException) {
                error = failure.error
            }
        }
    }

    ModalCard(onDismissRequest = onDismiss, modifier = Modifier.width(DialogWidth)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DialogTitle("Matter pairing · $device", onDismiss)
            val known = credentials
            if (known != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    QrCard(known.qrPayload)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FieldLabel("Manual pairing code")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = known.manualCode,
                                color = MCUHomeTheme.colors.ink,
                                fontFamily = MCUHomeTheme.typography.mono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                            )
                            CopyButton("the manual pairing code") {
                                scope.launch { clipboard.setClipEntry(ClipEntry.withPlainText(known.manualCode)) }
                            }
                        }
                        FieldLabel("QR payload")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = known.qrPayload,
                                color = MCUHomeTheme.colors.ink,
                                fontFamily = MCUHomeTheme.typography.mono,
                                fontSize = 12.5.sp,
                            )
                            CopyButton("the QR payload") {
                                scope.launch { clipboard.setClipEntry(ClipEntry.withPlainText(known.qrPayload)) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FieldLabel("Discriminator")
                                Text(
                                    text = known.discriminator.toString(),
                                    color = MCUHomeTheme.colors.ink,
                                    fontFamily = MCUHomeTheme.typography.body,
                                    fontSize = 13.sp,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FieldLabel("Passcode")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (revealed) known.passcode.toString() else PASSCODE_MASK,
                                        color = MCUHomeTheme.colors.ink,
                                        fontFamily = MCUHomeTheme.typography.mono,
                                        fontSize = 13.sp,
                                    )
                                    MCUHomeIconButton(
                                        icon = if (revealed) MCUHomeIcons.eyeOff else MCUHomeIcons.eye,
                                        contentDescription = if (revealed) "Hide the passcode" else "Show the passcode",
                                        onClick = { revealed = !revealed },
                                    )
                                }
                            }
                        }
                        Notice(
                            tone = PillTone.Info,
                            message = "Stored in ${known.secretsFile}. The values are compiled into the firmware — " +
                                "after drawing new ones, build and flash again.",
                            icon = MCUHomeIcons.infoCircle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            error?.let { ErrorNotice(it, Modifier.fillMaxWidth()) }
            if (confirmDraw) {
                Notice(
                    tone = PillTone.Warning,
                    title = "Draw new credentials?",
                    message = "Every code already printed or handed out stops working, and the device has to be " +
                        "built and flashed again.",
                    icon = MCUHomeIcons.warningTriangle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            hint?.let {
                Text(
                    text = it,
                    color = MCUHomeTheme.colors.muted,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 12.5.sp,
                )
            }
            PairingFooter(hasCredentials = known != null, confirmDraw = confirmDraw) { action ->
                when (action) {
                    PairingAction.Draw -> confirmDraw = true
                    PairingAction.ConfirmDraw -> draw(force = known != null)
                    PairingAction.KeepThem -> confirmDraw = false
                    PairingAction.Print -> hint = "Print the page with the browser's own print dialog (Ctrl+P)."
                    PairingAction.Done -> onDismiss()
                }
            }
        }
    }
}

/** What the pairing dialog's footer can start. */
private enum class PairingAction { Draw, ConfirmDraw, KeepThem, Print, Done }

@Composable
private fun PairingFooter(
    hasCredentials: Boolean,
    confirmDraw: Boolean,
    onAction: (PairingAction) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (confirmDraw) {
            SecondaryButton(text = "Keep them", onClick = { onAction(PairingAction.KeepThem) })
            Box(Modifier.weight(1f))
            PrimaryButton(text = "Draw new credentials", onClick = { onAction(PairingAction.ConfirmDraw) })
        } else {
            SecondaryButton(
                text = if (hasCredentials) "Draw new credentials…" else "Draw credentials…",
                onClick = { onAction(PairingAction.Draw) },
            )
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "Print",
                    onClick = { onAction(PairingAction.Print) },
                    enabled = hasCredentials,
                )
                PrimaryButton(text = "Done", onClick = { onAction(PairingAction.Done) })
            }
        }
    }
}

/** The code itself, on the light card a scanner expects. */
@Composable
private fun QrCard(payload: String) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier = Modifier
            .size(QrCardSize)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        QrCodeImage(
            payload = payload,
            contentDescription = "The commissioning code as a QR code",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CopyButton(what: String, onClick: () -> Unit) {
    MCUHomeIconButton(
        icon = MCUHomeIcons.copy,
        contentDescription = "Copy $what",
        onClick = onClick,
        bordered = true,
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 12.sp,
    )
}

/** A dialog's title line, with the close button the design puts opposite it. */
@Composable
fun DialogTitle(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = MCUHomeTheme.colors.ink,
            fontFamily = MCUHomeTheme.typography.heading,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Box(Modifier.weight(1f))
        MCUHomeIconButton(
            icon = MCUHomeIcons.close,
            contentDescription = "Close this dialog",
            onClick = onDismiss,
        )
    }
}
