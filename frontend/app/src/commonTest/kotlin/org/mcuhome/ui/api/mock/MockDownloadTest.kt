// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.api.mock

import kotlinx.coroutines.test.runTest
import org.mcuhome.ui.api.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MockDownloadTest {
    @Test
    fun the_files_of_a_last_good_build_can_be_listed_without_that_build_having_run_here() = runTest {
        val api = MockApi(scope = backgroundScope, startingBuild = false)
        val detail = api.device.get("kitchen-sensor")
        val buildId = detail.lastGoodBuild?.buildId ?: error("the sample device has a last good build")
        assertEquals(detail.artifacts.map { it.path }, api.build.artifacts(buildId).map { it.path })
    }

    @Test
    fun a_download_answers_with_the_file_name_and_something_the_browser_can_fetch() = runTest {
        val api = MockApi(scope = backgroundScope, startingBuild = false)
        val detail = api.device.get("kitchen-sensor")
        val buildId = detail.lastGoodBuild?.buildId ?: error("the sample device has a last good build")
        val download = api.build.download(buildId, "firmware.signed.bin")
        assertEquals("firmware.signed.bin", download.fileName)
        assertTrue(download.url.startsWith("data:text/plain;charset=utf-8,"))
        assertTrue("firmware.signed.bin" in download.url.replace("%20", " "))
    }

    @Test
    fun asking_for_a_file_a_build_never_produced_is_refused() = runTest {
        val api = MockApi(scope = backgroundScope, startingBuild = false)
        val buildId = api.device.get("kitchen-sensor").lastGoodBuild?.buildId.orEmpty()
        assertFailsWith<ApiException> { api.build.download(buildId, "nothing.bin") }
    }
}
