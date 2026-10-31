package com.joetr.mira.domain

import kotlin.jvm.JvmInline
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * A calendar day, as the ISO string `YYYY-MM-DD`.
 *
 * Mira stores the day an entry belongs to rather than deriving it from a timestamp, so this is a
 * first-class type: it sorts lexicographically, it is what the database indexes, and it is what the
 * archive filenames are built from. Constructing one from an instant requires naming a time zone,
 * which is the whole point — there is no zone-free way to ask what day something happened on.
 */
@Serializable
@JvmInline
public value class LocalDateKey(public val value: String) : Comparable<LocalDateKey> {

    public val year: Int get() = value.substring(0, 4).toInt()
    public val monthNumber: Int get() = value.substring(5, 7).toInt()
    public val dayOfMonth: Int get() = value.substring(8, 10).toInt()

    /** The `YYYY-MM` key for the month this day sits in. */
    public val monthKey: MonthKey get() = MonthKey(value.substring(0, 7))

    public fun toLocalDate(): LocalDate = LocalDate.parse(value)

    override fun compareTo(other: LocalDateKey): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        public fun of(date: LocalDate): LocalDateKey = LocalDateKey(date.toString())

        /** The calendar day [epochMillis] falls on in [zone]. */
        @OptIn(ExperimentalTime::class)
        public fun of(epochMillis: Long, zone: TimeZone): LocalDateKey =
            of(Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date)

        public fun parseOrNull(raw: String?): LocalDateKey? {
            if (raw == null || raw.length != 10) return null
            if (raw[4] != '-' || raw[7] != '-') return null
            return runCatching { LocalDate.parse(raw) }.getOrNull()?.let { LocalDateKey(raw) }
        }
    }
}

/** A calendar month, as `YYYY-MM`. */
@Serializable
@JvmInline
public value class MonthKey(public val value: String) : Comparable<MonthKey> {

    public val year: Int get() = value.substring(0, 4).toInt()
    public val monthNumber: Int get() = value.substring(5, 7).toInt()

    /** First day of this month. */
    public val firstDay: LocalDateKey get() = LocalDateKey("$value-01")

    /** Last day of this month, accounting for leap years. */
    public val lastDay: LocalDateKey
        get() = LocalDateKey.of(LocalDate(year, monthNumber, lengthOfMonth(year, monthNumber)))

    public fun plusMonths(count: Int): MonthKey {
        val total = year * 12 + (monthNumber - 1) + count
        return of(total.floorDiv(12), total.mod(12) + 1)
    }

    public fun minusMonths(count: Int): MonthKey = plusMonths(-count)

    override fun compareTo(other: MonthKey): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        public fun of(year: Int, month: Int): MonthKey =
            MonthKey("${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}")

        public fun of(date: LocalDate): MonthKey = of(date.year, date.month.number)

        public fun parseOrNull(raw: String?): MonthKey? {
            if (raw == null || raw.length != 7 || raw[4] != '-') return null
            val year = raw.substring(0, 4).toIntOrNull() ?: return null
            val month = raw.substring(5, 7).toIntOrNull() ?: return null
            if (month !in 1..12) return null
            return of(year, month)
        }
    }
}

public fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> error("Invalid month: $month")
}

public fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

/** Reads the device's current zone. Separated so tests can hand in a fixed one. */
public interface ClockZone {
    public fun nowMillis(): Long
    public fun zone(): TimeZone

    public fun today(): LocalDateKey = LocalDateKey.of(nowMillis(), zone())
    public fun zoneId(): String = zone().id
}

@OptIn(ExperimentalTime::class)
public class SystemClockZone : ClockZone {
    override fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    override fun zone(): TimeZone = TimeZone.currentSystemDefault()
}
