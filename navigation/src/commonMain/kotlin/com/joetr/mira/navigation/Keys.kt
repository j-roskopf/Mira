package com.joetr.mira.navigation

import kotlinx.serialization.Serializable

/** Calendar month grid — the home screen. [month] is `YYYY-MM`; null means the current month. */
@Serializable
public data class CalendarKey(val month: String? = null)

/** All twelve months of [year] as mini heatmaps. */
@Serializable
public data class YearKey(val year: Int)

/** Every entry for one day. [date] is `YYYY-MM-DD`. */
@Serializable
public data class DayKey(val date: String)

/** A single entry in the reading view. */
@Serializable
public data class EntryKey(val id: String)

/**
 * Compose sheet. [entryId] null creates a new entry; [date] seeds the date field and defaults to
 * today. [initialCapture] opens straight into audio or video capture.
 */
@Serializable
public data class ComposeKey(
    val entryId: String? = null,
    val date: String? = null,
    val initialCapture: String? = null,
)

@Serializable
public data object TimelineKey

@Serializable
public data class SearchKey(val query: String? = null)

@Serializable
public data object SettingsKey

@Serializable
public data object AuthKey

public sealed class TopLevelDestination {
    public data object Calendar : TopLevelDestination()
    public data object Timeline : TopLevelDestination()
    public data object Search : TopLevelDestination()
    public data object Settings : TopLevelDestination()
}

/** Capture modes accepted by [ComposeKey.initialCapture]. */
public object CaptureMode {
    public const val TEXT: String = "text"
    public const val AUDIO: String = "audio"
    public const val VIDEO: String = "video"
}
