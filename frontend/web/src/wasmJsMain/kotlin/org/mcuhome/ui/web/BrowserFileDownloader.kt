// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.web

import org.mcuhome.ui.api.ArtifactDownload
import org.mcuhome.ui.download.FileDownloader
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Handing a build's file to the browser.
 *
 * The API answers a download with a URL and the name the file should
 * have. The bytes are fetched rather than linked to, and handed on as a
 * blob: a link straight to the URL would open the file in a tab as often
 * as it would save it, would lose the file name whenever the server does
 * not spell one out, and would leave a partially typed URL in the address
 * bar if anything went wrong. Fetching also keeps the request on the same
 * session as every other call.
 *
 * The object URL is released again once the browser has taken the blob;
 * releasing it in the same turn as the click can cancel the download in
 * some browsers, which is why it happens a moment later.
 */
class BrowserFileDownloader : FileDownloader {
    override fun download(artifact: ArtifactDownload) {
        startBlobDownload(artifact.url, artifact.fileName)
    }
}

// The two parameters are read by the JavaScript body, which no Kotlin
// analysis can see into.
@Suppress("UnusedParameter")
@OptIn(ExperimentalWasmJsInterop::class)
private fun startBlobDownload(url: String, fileName: String): Unit = js(
    """{
        fetch(url)
            .then(function (response) { return response.blob(); })
            .then(function (blob) {
                const objectUrl = URL.createObjectURL(blob);
                const link = document.createElement('a');
                link.href = objectUrl;
                link.download = fileName;
                document.body.appendChild(link);
                link.click();
                link.remove();
                setTimeout(function () { URL.revokeObjectURL(objectUrl); }, 1000);
            });
    }""",
)
