// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import kotlinx.browser.document
import org.mcuhome.ui.App

/**
 * Starts the interface in the browser and ties its navigation to the
 * browser's own: the address bar shows the open screen, and the back and
 * forward buttons move through it. The routes are appended to whatever
 * address the page was served from, so the same build works at the site
 * root and under a base path.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    val body = document.body ?: return
    ComposeViewport(body) {
        App(onNavHostReady = { navController -> navController.bindToBrowserNavigation() })
    }
}
