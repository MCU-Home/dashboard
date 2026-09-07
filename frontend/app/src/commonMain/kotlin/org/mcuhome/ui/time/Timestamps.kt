// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.time

// How the interface writes a point in time and a length of time.
//
// Everything here is a pure function of the instants it is given, so what
// a screen shows can be asserted in a test without a clock.
//
// Times are read in UTC. The server sends instants rather than rendered
// strings, and the time zone of the machine the interface runs on is not
// applied here — that offset belongs in one place, next to the connection
// that delivers the instants.

private const val SECOND_MILLIS = 1_000L
private const val MINUTE_MILLIS = 60 * SECOND_MILLIS
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/** How many days back are still written as "n days ago" rather than as a date. */
private const val RELATIVE_DAYS = 6

/**
 * A build's or a job's time, as the tables and the jobs popover write it:
 * "today 12:41", "yesterday 18:07", "3 days ago", and a plain date for
 * anything older.
 */
fun formatTimestamp(atEpochMillis: Long, nowEpochMillis: Long): String {
    val days = epochDay(nowEpochMillis) - epochDay(atEpochMillis)
    return when {
        days <= 0L -> "today ${formatTimeOfDay(atEpochMillis)}"
        days == 1L -> "yesterday ${formatTimeOfDay(atEpochMillis)}"
        days <= RELATIVE_DAYS -> "$days days ago"
        else -> formatDate(atEpochMillis)
    }
}

/**
 * The same instant where the day is already clear from the context: the
 * bare time today, the full form on any other day. The jobs popover reads
 * "finished 12:41" for a job of this morning and "finished yesterday
 * 18:12" for one of the day before.
 */
fun formatTimestampCompact(atEpochMillis: Long, nowEpochMillis: Long): String =
    if (epochDay(nowEpochMillis) == epochDay(atEpochMillis)) {
        formatTimeOfDay(atEpochMillis)
    } else {
        formatTimestamp(atEpochMillis, nowEpochMillis)
    }

/** The hour and minute of an instant: "12:41". */
fun formatTimeOfDay(epochMillis: Long): String {
    val millisOfDay = epochMillis.mod(DAY_MILLIS)
    val hours = millisOfDay / HOUR_MILLIS
    val minutes = (millisOfDay % HOUR_MILLIS) / MINUTE_MILLIS
    return "${twoDigits(hours)}:${twoDigits(minutes)}"
}

/** The calendar date of an instant: "2026-08-24". */
fun formatDate(epochMillis: Long): String {
    val (year, month, day) = civilFromEpochDay(epochDay(epochMillis))
    return "$year-${twoDigits(month.toLong())}-${twoDigits(day.toLong())}"
}

/**
 * How long something took: "42 s", "4 min 12 s", "1 h 03 min". Anything
 * shorter than a second is written as "0 s" rather than in milliseconds —
 * nothing the interface times is that short.
 */
fun formatDuration(millis: Long): String {
    val total = if (millis < 0) 0 else millis
    val hours = total / HOUR_MILLIS
    val minutes = (total % HOUR_MILLIS) / MINUTE_MILLIS
    val seconds = (total % MINUTE_MILLIS) / SECOND_MILLIS
    return when {
        hours > 0 -> "$hours h ${twoDigits(minutes)} min"
        minutes > 0 -> "$minutes min $seconds s"
        else -> "$seconds s"
    }
}

private fun epochDay(epochMillis: Long): Long = epochMillis.floorDiv(DAY_MILLIS)

/** The smallest two-digit number: below it a leading zero is written. */
private const val TWO_DIGITS = 10

private fun twoDigits(value: Long): String = if (value < TWO_DIGITS) "0$value" else value.toString()

/**
 * The calendar date of a day counted from 1970-01-01, by the civil-date
 * algorithm every date library uses: shift the epoch to the start of a
 * 400-year era, count the day within the era, and read year, month and
 * day back out of it. The literals are the lengths of the era, the
 * century and the four-year cycle in days.
 */
@Suppress("MagicNumber")
private fun civilFromEpochDay(epochDay: Long): Triple<Long, Int, Int> {
    val shifted = epochDay + 719468
    val era = (if (shifted >= 0) shifted else shifted - 146096) / 146097
    val dayOfEra = shifted - era * 146097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPortion = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPortion + 2) / 5 + 1
    val month = if (monthPortion < 10) monthPortion + 3 else monthPortion - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return Triple(year, month.toInt(), day.toInt())
}
