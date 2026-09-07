// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.mcuhome.ui.api.Availability
import org.mcuhome.ui.api.DeviceDetail
import org.mcuhome.ui.api.FlashMode
import org.mcuhome.ui.api.FlashOptions
import org.mcuhome.ui.api.FlashRequest
import org.mcuhome.ui.api.LocalMcuHomeApi
import org.mcuhome.ui.component.ControlHeight
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.ModalCard
import org.mcuhome.ui.component.NotAvailableNotice
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.formatByteSize
import org.mcuhome.ui.theme.MCUHomeTheme
import org.mcuhome.ui.time.formatTimestamp

private val DialogWidth = 560.dp

/** The roles of a build's artifacts that can be written to a board. */
private val FLASHABLE_ROLES = setOf("firmware", "ota-image")

/**
 * Writing an image onto a board: which image, over which path, through
 * which port.
 *
 * Nothing behind it works yet — the workbench has no flash command — and
 * the dialog says so where the answer would be rather than by refusing
 * after the fact. Everything it can already show, it shows: the images
 * the last good build produced are real, and when the capability arrives
 * the same layout is filled from `flash/options` instead.
 */
@Composable
fun FlashDialog(
    detail: DeviceDetail,
    initialMode: FlashMode,
    nowEpochMillis: Long,
    onDismiss: () -> Unit,
    buildRunning: Boolean = false,
) {
    val api = LocalMcuHomeApi.current
    val scope = rememberCoroutineScope()
    val device = detail.summary.name
    var options by remember { mutableStateOf<Availability<FlashOptions>?>(null) }
    var mode by remember { mutableStateOf(initialMode) }
    var image by remember { mutableStateOf(detail.artifacts.firstOrNull { it.role in FLASHABLE_ROLES }?.path) }
    var refusal by remember { mutableStateOf<Availability.NotAvailable?>(null) }

    LaunchedEffect(device) { options = api.flash.options(device) }

    fun start() {
        scope.launch {
            val answer = api.flash.start(
                FlashRequest(device = device, imagePath = image.orEmpty(), mode = mode),
            )
            refusal = answer as? Availability.NotAvailable
        }
    }

    fun setup() {
        scope.launch { refusal = api.setup.start(device) as? Availability.NotAvailable }
    }

    ModalCard(onDismissRequest = onDismiss, modifier = Modifier.width(DialogWidth)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogTitle("Flash $device", onDismiss)

            SectionLabel("Firmware")
            FirmwareChoice(
                images = flashImages(detail, nowEpochMillis),
                selected = image,
                onSelect = { image = it },
            )
            if (buildRunning) {
                Hint("A build is running right now; when it finishes, its image appears here.")
            }

            SectionLabel("Mode")
            ModeCard(
                title = "Recovery (USB)",
                description = "Writes the image over the board's serial/USB port. Works on a blank or broken device.",
                selected = mode == FlashMode.Recovery,
                onSelect = { mode = FlashMode.Recovery },
            )
            ModeCard(
                title = "Over the air (Matter OTA)",
                description = "Publishes the image to the OTA provider; the device updates on its next check-in.",
                selected = mode == FlashMode.Ota,
                onSelect = { mode = FlashMode.Ota },
            )

            SectionLabel("Port")
            PortField(options)
            (options as? Availability.NotAvailable)?.let { NotAvailableNotice(it, Modifier.fillMaxWidth()) }
            refusal?.let { NotAvailableNotice(it, Modifier.fillMaxWidth()) }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(text = "Run first-time setup…", onClick = { setup() })
                Box(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(text = "Cancel", onClick = onDismiss)
                    PrimaryButton(text = "Flash", onClick = { start() }, icon = MCUHomeIcons.bolt)
                }
            }
        }
    }
}

/** One image the dialog offers: what it is called, and what is known about it. */
private data class FlashImageRow(val path: String, val fileName: String, val subtitle: String)

/**
 * The images of the last good build, as the dialog lists them.
 *
 * The list comes from the device's own artifacts while `flash/options`
 * cannot answer. It is derived here rather than in the row so that the
 * row draws what it is given and nothing else.
 */
private fun flashImages(detail: DeviceDetail, nowEpochMillis: Long): List<FlashImageRow> {
    val built = detail.lastGoodBuild?.finishedAtEpochMillis?.let { formatTimestamp(it, nowEpochMillis) }
    val signed = detail.lastGoodBuild?.signed == true
    return detail.artifacts.filter { it.role in FLASHABLE_ROLES }.mapIndexed { index, artifact ->
        val state = if (signed || "signed" in artifact.fileName) "signed" else "unsigned"
        val size = formatByteSize(artifact.sizeBytes)
        FlashImageRow(
            path = artifact.path,
            fileName = artifact.fileName,
            subtitle = if (index == 0) {
                listOfNotNull("last good build", built, size).joinToString(" · ")
            } else {
                "$state · same build · $size"
            },
        )
    }
}

/** The images the last good build left, one of which is written. */
@Composable
private fun FirmwareChoice(
    images: List<FlashImageRow>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val colors = MCUHomeTheme.colors
    if (images.isEmpty()) {
        Hint("This device has no build output yet. Build it first — a flash writes what a build produced.")
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
    ) {
        images.forEach { image ->
            val isSelected = image.path == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isSelected) colors.accentTint else colors.surface)
                    .clickable { onSelect(image.path) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSelect(image.path) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = colors.accent,
                        unselectedColor = colors.border,
                    ),
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = image.fileName,
                    color = colors.ink,
                    fontFamily = MCUHomeTheme.typography.mono,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
                Box(Modifier.weight(1f))
                Text(
                    text = image.subtitle,
                    color = colors.muted,
                    fontFamily = MCUHomeTheme.typography.body,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** One of the two ways an image reaches a board. */
@Composable
private fun ModeCard(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accentTint else colors.surface)
            .border(1.dp, if (selected) colors.accent else colors.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.border),
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Text(
                text = description,
                color = colors.muted,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 12.5.sp,
            )
        }
    }
}

/**
 * Where the ports would be. The field keeps its place in the layout so
 * that the dialog reads the same once there is something to put in it.
 */
@Composable
private fun PortField(options: Availability<FlashOptions>?) {
    val colors = MCUHomeTheme.colors
    val ports = (options as? Availability.Available)?.value?.ports.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ControlHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ports.firstOrNull()?.let { "${it.path} — ${it.description}" } ?: "No port can be listed yet",
            color = if (ports.isEmpty()) colors.muted else colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
        )
        Box(Modifier.weight(1f))
        Icon(
            imageVector = MCUHomeIcons.chevronDown,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MCUHomeTheme.colors.ink,
        fontFamily = MCUHomeTheme.typography.body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = MCUHomeTheme.colors.muted,
        fontFamily = MCUHomeTheme.typography.body,
        fontSize = 12.5.sp,
    )
}
