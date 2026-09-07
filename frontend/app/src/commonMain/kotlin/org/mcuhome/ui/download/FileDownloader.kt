// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.download

import androidx.compose.runtime.staticCompositionLocalOf
import org.mcuhome.ui.api.ArtifactDownload

/**
 * How a file the server offers reaches the person in front of the screen.
 *
 * The API answers a download with a URL and a file name, and what happens
 * with those two is the one thing that is genuinely different per
 * platform: a browser fetches the URL and hands the bytes to its own
 * download manager, a desktop application would write a file and open a
 * folder. `:app` therefore states the intent and nothing else; the entry
 * point of each platform installs the implementation.
 */
fun interface FileDownloader {
    fun download(artifact: ArtifactDownload)
}

/**
 * The downloader in scope. The default does nothing, which is the honest
 * behaviour for a preview or a test: there is nowhere to put a file.
 */
val LocalFileDownloader = staticCompositionLocalOf { FileDownloader { } }
