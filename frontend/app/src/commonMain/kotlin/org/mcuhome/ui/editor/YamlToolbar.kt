// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mcuhome.ui.component.MCUHomeIcons
import org.mcuhome.ui.component.SecondaryButton
import org.mcuhome.ui.component.TouchControlHeight
import org.mcuhome.ui.component.handCursor
import org.mcuhome.ui.component.topBorder
import org.mcuhome.ui.theme.MCUHomeTheme

/** The bar the keys sit in; the keys themselves take a finger's full square. */
private val ToolbarHeight = 48.dp

private val KeyShape = RoundedCornerShape(8.dp)

/**
 * The keys a phone's keyboard does not have.
 *
 * A YAML file is written with a colon, a dash, two spaces of indentation
 * and the occasional `!secret`, and every one of those is two or three
 * taps away on a phone's keyboard. The bar puts them one tap away and
 * sits directly above the keyboard while the editor has the focus.
 *
 * Undo is here for the same reason: the keyboard has no Ctrl to press Z
 * with. Done gives the focus back to the page, which is what makes the
 * keyboard go away.
 */
// The undo history of a `TextFieldState` is still experimental foundation
// API; there is no other way to reach the editor's own undo stack, and a
// toolbar that offers undo has to reach it.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YamlToolbar(
    state: TextFieldState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    fun apply(edit: TextEdit) {
        state.edit {
            replace(0, length, edit.text)
            selection = TextRange(edit.selectionStart, edit.selectionEnd)
        }
    }

    fun transform(action: (String, Int, Int) -> TextEdit) {
        val text = state.text.toString()
        apply(action(text, state.selection.min, state.selection.max))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ToolbarHeight)
            .background(colors.surface)
            .topBorder()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Done keeps the right edge whatever else has to give way: it is
        // the way out of the editing mode, and a way out that has to be
        // scrolled to is not one.
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconKey(MCUHomeIcons.outdent, "Outdent") { transform(::outdentSelection) }
            IconKey(MCUHomeIcons.indent, "Indent") { transform(::indentSelection) }
            TextKey(":") { transform { text, start, end -> insertAtSelection(text, start, end, ": ") } }
            TextKey("-") { transform { text, start, end -> insertAtSelection(text, start, end, "- ") } }
            TextKey("!secret") { transform { text, start, end -> insertAtSelection(text, start, end, "!secret ") } }
            IconKey(MCUHomeIcons.undo, "Undo") { state.undoState.undo() }
        }
        Box(Modifier.size(8.dp))
        SecondaryButton(text = "Done", onClick = onDone, height = TouchControlHeight)
    }
}

@Composable
private fun IconKey(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier = Modifier
            .size(TouchControlHeight)
            .background(colors.surface, KeyShape)
            .border(1.dp, colors.border, KeyShape)
            .handCursor().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = colors.ink, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TextKey(label: String, onClick: () -> Unit) {
    val colors = MCUHomeTheme.colors
    Box(
        modifier = Modifier
            .height(TouchControlHeight)
            .defaultMinSize(minWidth = TouchControlHeight)
            .background(colors.surface, KeyShape)
            .border(1.dp, colors.border, KeyShape)
            .handCursor().clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.mono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}
