// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.time

import kotlin.test.Test
import kotlin.test.assertEquals

/** 2026-09-07 12:52:06 UTC — the instant the sample project is frozen at. */
private const val NOW = 1_788_785_526_000L

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * How the tables and the jobs popover write a time. The values are the
 * ones the design's screens show, so a change in the wording shows up
 * here first.
 */
class TimestampsTest {
    @Test
    fun anInstantOfTodayIsWrittenWithTheWordToday() {
        assertEquals("today 12:41", formatTimestamp(NOW - 11 * MINUTE, NOW))
    }

    @Test
    fun anInstantOfTheDayBeforeIsWrittenAsYesterday() {
        assertEquals("yesterday 18:07", formatTimestamp(NOW - 18 * HOUR - 45 * MINUTE, NOW))
    }

    @Test
    fun anInstantOfThisWeekIsWrittenInDays() {
        assertEquals("3 days ago", formatTimestamp(NOW - 2 * DAY - 20 * HOUR - 32 * MINUTE, NOW))
    }

    @Test
    fun anythingOlderThanAWeekIsWrittenAsADate() {
        assertEquals("2026-08-24", formatTimestamp(NOW - 14 * DAY, NOW))
    }

    @Test
    fun aTimeStillToComeCountsAsToday() {
        assertEquals("today 20:00", formatTimestamp(NOW + 7 * HOUR + 7 * MINUTE + 54_000L, NOW))
    }

    @Test
    fun theCompactFormDropsTheWordTodayAndKeepsEveryOtherDay() {
        assertEquals("12:41", formatTimestampCompact(NOW - 11 * MINUTE, NOW))
        assertEquals("yesterday 18:12", formatTimestampCompact(NOW - 18 * HOUR - 40 * MINUTE, NOW))
    }

    @Test
    fun theTimeOfDayIsAlwaysTwoDigitsEach() {
        assertEquals("00:05", formatTimeOfDay(5 * MINUTE))
        assertEquals("09:07", formatTimeOfDay(9 * HOUR + 7 * MINUTE))
    }

    @Test
    fun datesAreWrittenTheWayTheyAreRead() {
        assertEquals("1970-01-01", formatDate(0))
        assertEquals("2026-09-07", formatDate(NOW))
        assertEquals("2024-02-29", formatDate(1_709_208_000_000L))
    }

    @Test
    fun durationsStateTheLargestUnitThatIsNotZero() {
        assertEquals("0 s", formatDuration(0))
        assertEquals("42 s", formatDuration(42 * 1_000L))
        assertEquals("4 min 12 s", formatDuration(4 * MINUTE + 12_000L))
        assertEquals("1 h 03 min", formatDuration(HOUR + 3 * MINUTE))
    }

    @Test
    fun aNegativeDurationIsNoDuration() {
        assertEquals("0 s", formatDuration(-5_000L))
    }

    @Test
    fun build_output_is_timed_to_the_second() {
        assertEquals("12:51:56", formatTimeOfDaySeconds(1_788_785_516_000))
        assertEquals("00:00:00", formatTimeOfDaySeconds(0))
        assertEquals("23:59:59", formatTimeOfDaySeconds(86_399_000))
    }
}
