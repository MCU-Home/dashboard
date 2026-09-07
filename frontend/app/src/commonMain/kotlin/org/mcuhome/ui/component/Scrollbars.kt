// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mcuhome.ui.theme.MCUHomeTheme

/** How wide the bar is, and how short its thumb may become on a long document. */
private val ScrollbarThickness = 8.dp
private val ScrollbarMinimumThumb = 28.dp

/** How long the thumb takes to brighten under the pointer. */
private const val SCROLLBAR_HOVER_MILLIS = 120

/**
 * The one scrollbar this interface draws: a thin rounded thumb in the
 * pin-grey of the brand, no track, brightening to the muted tone under
 * the pointer.
 *
 * A canvas has no scrollbars of its own, so anything that scrolls has to
 * draw one or leave the reader guessing whether there is more below. It
 * is deliberately quiet — the content is what matters — and it is never
 * drawn when everything already fits.
 */
@Composable
fun mcuHomeScrollbarStyle(): ScrollbarStyle {
    val colors = MCUHomeTheme.colors
    return ScrollbarStyle(
        minimalHeight = ScrollbarMinimumThumb,
        thickness = ScrollbarThickness,
        shape = RoundedCornerShape(ScrollbarThickness / 2),
        hoverDurationMillis = SCROLLBAR_HOVER_MILLIS,
        unhoverColor = colors.pinGray,
        hoverColor = colors.muted,
    )
}

/** The vertical bar of a plain scrolling container; nothing when it does not scroll. */
@Composable
fun ThinVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    if (state.maxValue <= 0) return
    VerticalScrollbar(
        adapter = remember(state) { ScrollbarAdapter(state) },
        modifier = modifier,
        style = mcuHomeScrollbarStyle(),
    )
}

/** The horizontal bar of a plain scrolling container; nothing when it does not scroll. */
@Composable
fun ThinHorizontalScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    if (state.maxValue <= 0) return
    HorizontalScrollbar(
        adapter = remember(state) { ScrollbarAdapter(state) },
        modifier = modifier,
        style = mcuHomeScrollbarStyle(),
    )
}

/** The same bar beside a lazy list, which reports its extent item by item. */
@Composable
fun ThinVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    if (!state.canScrollForward && !state.canScrollBackward) return
    VerticalScrollbar(
        adapter = remember(state) { ScrollbarAdapter(state) },
        modifier = modifier,
        style = mcuHomeScrollbarStyle(),
    )
}
