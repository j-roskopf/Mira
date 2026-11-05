package com.joetr.mira.navigation

private val MONTH = Regex("""^\d{4}-\d{2}$""")
private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val YEAR = Regex("""^\d{4}$""")

/**
 * Maps a browser path onto a navigation key.
 *
 * `/` or `/calendar` → current month, `/calendar/2026-10` → that month,
 * `/day/2026-10-30`, `/entry/<id>`, `/year/2026`, `/timeline`, `/media`, `/search?q=` ,
 * `/settings`.
 */
public fun parseWebRoute(path: String): Any? {
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    return when (val head = segments.firstOrNull()) {
        null -> CalendarKey()
        "calendar" -> CalendarKey(segments.getOrNull(1)?.takeIf { MONTH.matches(it) })
        "day" -> segments.getOrNull(1)?.takeIf { DATE.matches(it) }?.let { DayKey(it) }
        "entry" -> segments.getOrNull(1)?.let { EntryKey(it) }
        "year" -> segments.getOrNull(1)?.takeIf { YEAR.matches(it) }?.let { YearKey(it.toInt()) }
        "timeline" -> TimelineKey
        "media" -> MediaKey
        "search" -> SearchKey(segments.getOrNull(1))
        "settings" -> SettingsKey
        // A bare `YYYY-MM-DD` is a day; a bare `YYYY-MM` is a month. Shareable shorthand.
        else -> when {
            DATE.matches(head) -> DayKey(head)
            MONTH.matches(head) -> CalendarKey(head)
            else -> null
        }
    }
}

/** Inverse of [parseWebRoute], for pushing history state on web. */
public fun webRouteFor(key: Any): String = when (key) {
    is CalendarKey -> key.month?.let { "/calendar/$it" } ?: "/"
    is DayKey -> "/day/${key.date}"
    is EntryKey -> "/entry/${key.id}"
    is YearKey -> "/year/${key.year}"
    TimelineKey -> "/timeline"
    MediaKey -> "/media"
    is SearchKey -> key.query?.let { "/search/$it" } ?: "/search"
    SettingsKey -> "/settings"
    else -> "/"
}
