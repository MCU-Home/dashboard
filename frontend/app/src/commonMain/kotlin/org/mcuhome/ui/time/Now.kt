// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.time

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** How often the interface's idea of "now" is refreshed while a screen is open. */
private const val REFRESH_MILLIS = 30_000L

/** The instant this interface is running at, from the machine it runs on. */
fun systemNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * "Now", kept current while a screen is open.
 *
 * Times are written relative to it — "today 12:41", "3 days ago" — so a
 * page that stays open past midnight has to notice. Half a minute is
 * often enough for text that changes by the day and cheap enough to be
 * unnoticeable.
 */
@Composable
fun rememberNowEpochMillis(): State<Long> = produceState(systemNowEpochMillis()) {
    while (true) {
        delay(REFRESH_MILLIS)
        value = systemNowEpochMillis()
    }
}
