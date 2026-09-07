// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.api.ProjectOption
import org.mcuhome.ui.component.ControlHeight
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.MCUHomeMenuItem
import org.mcuhome.ui.component.MCUHomeTextField
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.theme.MCUHomeTheme

private val FieldShape = RoundedCornerShape(8.dp)

/**
 * The value of one option, in whatever shape the option has.
 *
 * An option with a fixed vocabulary is a menu — offering a free text
 * field for `container` or `subprocess` would only invite a typo the
 * server has to refuse. Everything else is a field that writes when it
 * loses the focus or when Enter is pressed, so a value is never written
 * halfway through being typed.
 */
@Composable
fun OptionValueField(
    option: ProjectOption,
    onSet: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !option.editable -> ReadOnlyValue(option, modifier)
        option.choices.isNotEmpty() -> ChoiceValue(option, onSet, modifier)
        else -> TextValue(option, onSet, modifier)
    }
}

@Composable
private fun ReadOnlyValue(option: ProjectOption, modifier: Modifier) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier = modifier
            .height(ControlHeight)
            .clip(FieldShape)
            .background(colors.backgroundAlt)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = option.value ?: UNSET_OPTION_PLACEHOLDER,
            color = colors.muted,
            fontFamily = MCUHomeTheme.typography.mono,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun TextValue(
    option: ProjectOption,
    onSet: (String) -> Unit,
    modifier: Modifier,
) {
    val state: TextFieldState = rememberTextFieldState()
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(option.value, option.origin) {
        if (!focused) state.setTextAndPlaceCursorAtEnd(option.value.orEmpty())
    }

    fun commit() {
        val typed = state.text.toString()
        if (typed.isNotEmpty() && typed != option.value) onSet(typed)
    }

    MCUHomeTextField(
        state = state,
        modifier = modifier
            .onFocusChanged { focus ->
                if (focused && !focus.isFocused) commit()
                focused = focus.isFocused
            }
            .onPreviewKeyEvent { event ->
                val enter = event.type == KeyEventType.KeyDown && event.key == Key.Enter
                if (enter) commit()
                enter
            },
        placeholder = UNSET_OPTION_PLACEHOLDER,
        mono = true,
    )
}

@Composable
private fun ChoiceValue(
    option: ProjectOption,
    onSet: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MCUHomeTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ControlHeight)
                .clip(FieldShape)
                .background(colors.surface)
                .border(1.dp, colors.border, FieldShape)
                .handCursor()
                .clickable { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = option.value ?: UNSET_OPTION_PLACEHOLDER,
                color = if (option.value == null) colors.muted else colors.ink,
                fontFamily = MCUHomeTheme.typography.mono,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = MCUHomeIcons.chevronDown,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            option.choices.forEach { choice ->
                MCUHomeMenuItem(
                    label = choice,
                    onClick = {
                        open = false
                        onSet(choice)
                    },
                )
            }
        }
    }
}
