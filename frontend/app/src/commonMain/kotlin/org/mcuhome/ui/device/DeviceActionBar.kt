// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mcuhome.ui.api.BuildMethod
import org.mcuhome.ui.api.FlashMode
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.PrimaryButton
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.TouchControlHeight
import org.mcuhome.ui.component.topBorder
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * The three actions a phone keeps within reach of a thumb.
 *
 * A desktop window puts six buttons above the editor; a phone has room
 * for the three that are used every time a file is edited. The other
 * three — signing, pairing, first-time setup — are in the "…" of the
 * header, which is where a phone expects the rest of a screen's actions
 * to be.
 *
 * The bar is at the bottom rather than at the top for the same reason:
 * the top of a phone is where a name goes and the bottom is where a
 * thumb is.
 */
@Composable
fun DeviceActionBar(
    actions: DeviceHeaderActions,
    modifier: Modifier = Modifier,
    defaultBuildMethod: BuildMethod = BuildMethod.Local,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MCUHomeTheme.colors.surface)
            .topBorder()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryButton(
            text = "Validate",
            onClick = actions.onValidate,
            icon = MCUHomeIcons.check,
            height = TouchControlHeight,
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(
            text = "Build",
            onClick = { actions.onBuild(defaultBuildMethod) },
            icon = MCUHomeIcons.hammer,
            height = TouchControlHeight,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            text = "Flash",
            onClick = { actions.onFlash(FlashMode.Recovery) },
            icon = MCUHomeIcons.bolt,
            height = TouchControlHeight,
            modifier = Modifier.weight(1f),
        )
    }
}
