package com.joetr.mira.ui

import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val MONTHS_SHORT = listOf(
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)

private val WEEKDAYS = listOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
)

/** Single-letter-ish weekday heads, Sunday first, matching the calendar grid. */
public val WEEKDAY_HEADS: List<String> = listOf("SU", "M", "TU", "W", "TH", "F", "SA")

public fun MonthKey.displayName(): String = MONTHS[monthNumber - 1]

public fun MonthKey.shortName(): String = MONTHS_SHORT[monthNumber - 1]

public fun monthShortName(month: Int): String = MONTHS_SHORT[month - 1]

/** `TUESDAY` for the day-screen title. */
public fun LocalDateKey.weekdayUpper(): String =
    WEEKDAYS[toLocalDate().dayOfWeek.isoDayNumber - 1].uppercase()

/** `30 OCT 2026` for the line beneath it. */
public fun LocalDateKey.displayDate(): String =
    "$dayOfMonth ${MONTHS_SHORT[monthNumber - 1]} $year"

/** `30 October` — used in timeline rows where the year is already in the section header. */
public fun LocalDateKey.displayDayMonth(): String = "$dayOfMonth ${MONTHS[monthNumber - 1]}"

/**
 * Column index for this date in a Sunday-first grid.
 *
 * kotlinx-datetime's [DayOfWeek] is ISO (Monday = 1), and the calendar renders Sunday first.
 */
public fun LocalDateKey.sundayFirstColumn(): Int = toLocalDate().dayOfWeek.isoDayNumber % 7

/** `11:47 PM` in the device's current zone. */
@OptIn(ExperimentalTime::class)
public fun formatTimestamp(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).time
    val hour24 = time.hour
    val hour = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val minute = time.minute.toString().padStart(2, '0')
    val suffix = if (hour24 < 12) "AM" else "PM"
    return "$hour:$minute $suffix"
}

/** `1:34` / `12:05` / `1:02:33` for media durations. */
public fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/** `4.2 MB`. Binary units, one decimal above KB. */
public fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = (value * 10).toLong() / 10.0
    return if (unit == 0) "${rounded.toInt()} ${units[unit]}" else "$rounded ${units[unit]}"
}
