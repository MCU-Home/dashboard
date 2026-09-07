// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.theme.MCUHomeTheme

private val FieldShape = RoundedCornerShape(8.dp)

/**
 * A single-line input: the device table's filter, the board search, the
 * two names in the New device dialog.
 *
 * Material's own text fields carry a floating label and a fixed height
 * that neither the toolbar row nor the dialog has room for, so the field
 * is a `BasicTextField` with the decoration this interface wants: an
 * outline in the border token, an optional icon in front, a placeholder,
 * and an outline in the error token while [invalid] holds.
 *
 * [mono] switches the text to the monospace role for values that are
 * identifiers rather than prose — a device name becomes a folder name and
 * a host name, and reads as one here.
 *
 * [height] is the row the field is drawn in; it grows where a finger
 * rather than a pointer has to hit it.
 */
@Composable
fun MCUHomeTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    mono: Boolean = false,
    invalid: Boolean = false,
    enabled: Boolean = true,
    height: Dp = ControlHeight,
) {
    val colors = MCUHomeTheme.colors
    val typography = MCUHomeTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val outline = when {
        invalid -> colors.error
        focused -> colors.accent
        else -> colors.border
    }
    val textStyle = TextStyle(
        fontFamily = if (mono) typography.mono else typography.body,
        fontSize = if (mono) 13.sp else 13.5.sp,
        color = colors.ink,
    )

    BasicTextField(
        state = state,
        modifier = modifier,
        enabled = enabled,
        textStyle = textStyle,
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(colors.accent),
        interactionSource = interactionSource,
        decorator = remember(placeholder, leadingIcon, outline, textStyle, height) {
            TextFieldDecorator { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .clip(FieldShape)
                        .background(colors.surface)
                        .border(width = 1.dp, color = outline, shape = FieldShape)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = colors.muted,
                            modifier = Modifier.size(15.dp).padding(end = 1.dp),
                        )
                    }
                    Box(Modifier.padding(start = if (leadingIcon != null) 8.dp else 0.dp)) {
                        if (state.text.isEmpty()) {
                            Text(text = placeholder, style = textStyle.copy(color = colors.muted))
                        }
                        innerTextField()
                    }
                }
            }
        },
    )
}
