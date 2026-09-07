// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.mcuhome.ui.theme.MCUHomeTheme

private val OverlayShape = RoundedCornerShape(10.dp)
private val DialogShape = RoundedCornerShape(12.dp)

/**
 * Hangs the panel under the control that opened it, right edges lined up,
 * and keeps it inside the window whatever size it turns out to be.
 *
 * Compose's own alignment places a popup *within* its anchor's bounds,
 * which for a chip in the top bar would draw the panel over the chip.
 * This is the placement a menu actually wants.
 */
private class BelowAnchor(private val offset: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val right = anchorBounds.right + offset.x
        val x = (right - popupContentSize.width).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val y = (anchorBounds.bottom + offset.y).coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
        return IntOffset(x, y)
    }
}

/**
 * A panel that hangs off the control that opened it: the jobs popover
 * under the jobs chip.
 *
 * It is positioned relative to the composable it is written inside, so
 * the anchor is wherever the caller places it rather than a coordinate
 * anyone has to keep in step with the layout. Clicking anywhere outside
 * it and pressing Escape both close it.
 */
@Composable
fun AnchoredPopover(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: IntOffset = IntOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MCUHomeTheme.colors
    val focusRequester = remember { FocusRequester() }
    Popup(
        popupPositionProvider = remember(offset) { BelowAnchor(offset) },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(
            modifier = modifier
                .shadow(elevation = 12.dp, shape = OverlayShape)
                .clip(OverlayShape)
                .background(colors.surface)
                .border(width = 1.dp, color = colors.border, shape = OverlayShape)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event -> dismissOnEscape(event.key, event.type, onDismissRequest) },
            content = content,
        )
    }
}

/**
 * A modal card in the middle of the window: New device, and the two small
 * confirmations the device table asks for.
 *
 * Escape closes it and Enter runs [onSubmit] where the caller has one and
 * the form is ready — a dialog that is finished with the keyboard needs
 * no reach for the pointer. Both are handled before the focused field
 * sees the key, so they work wherever the caret happens to be.
 */
@Composable
fun ModalCard(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MCUHomeTheme.colors
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = modifier
                .clip(DialogShape)
                .background(colors.surface)
                .border(width = 1.dp, color = colors.border, shape = DialogShape)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false

                        event.key == Key.Escape -> {
                            onDismissRequest()
                            true
                        }

                        event.key == Key.Enter && onSubmit != null -> {
                            onSubmit()
                            true
                        }

                        else -> false
                    }
                },
            content = content,
        )
    }
}

private fun dismissOnEscape(
    key: Key,
    type: KeyEventType,
    onDismissRequest: () -> Unit,
): Boolean {
    if (type != KeyEventType.KeyDown || key != Key.Escape) return false
    onDismissRequest()
    return true
}
