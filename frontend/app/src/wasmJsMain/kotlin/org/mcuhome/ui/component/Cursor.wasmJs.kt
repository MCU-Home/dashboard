// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.component

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.fromKeyword

/**
 * The resize pointers as the browser names them: Compose sets the CSS
 * `cursor` property of the canvas it draws on, so any keyword the browser
 * knows can be asked for by name.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual val VerticalResizeCursor: PointerIcon = PointerIcon.fromKeyword("ns-resize")

@OptIn(ExperimentalComposeUiApi::class)
internal actual val HorizontalResizeCursor: PointerIcon = PointerIcon.fromKeyword("ew-resize")
