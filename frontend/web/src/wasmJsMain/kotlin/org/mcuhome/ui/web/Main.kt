// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.mcuhome.ui.App
import org.mcuhome.ui.api.mock.MockApi

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
    // Until the back end exists, every screen runs against the in-memory
    // mock. Switching to the real client is a change to this one line.
    val api = MockApi(scope = CoroutineScope(SupervisorJob() + Dispatchers.Main))
    val downloader = BrowserFileDownloader()
    ComposeViewport(body) {
        App(
            api = api,
            onNavHostReady = { navController -> navController.bindToBrowserNavigation() },
            downloader = downloader,
        )
    }
}
