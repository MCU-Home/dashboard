// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.job

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import org.mcuhome.ui.api.EventsDropped
import org.mcuhome.ui.api.Job
import org.mcuhome.ui.api.JobAdded
import org.mcuhome.ui.api.JobChanged
import org.mcuhome.ui.api.JobsCleared
import org.mcuhome.ui.api.McuHomeApi

/**
 * Everything the server is doing or has done, kept current.
 *
 * It lives in the shell rather than on a screen: the chip in the top bar
 * shows it on every page, and a build that keeps running while the user
 * navigates has to stay visible. Like the device list it follows the
 * event stream and reads the whole list again after a gap in it.
 */
@Composable
fun rememberJobList(api: McuHomeApi): State<List<Job>> = produceState(emptyList(), api) {
    value = api.job.list()
    api.events.collect { event ->
        value = when (event) {
            is JobAdded -> value.filterNot { it.id == event.job.id } + event.job
            is JobChanged -> value.map { if (it.id == event.job.id) event.job else it }
            is JobsCleared -> value.filterNot { it.id in event.ids }
            is EventsDropped -> api.job.list()
            else -> value
        }
    }
}
