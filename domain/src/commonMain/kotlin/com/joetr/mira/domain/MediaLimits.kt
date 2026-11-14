package com.joetr.mira.domain

/**
 * Largest file the GitHub archive can commit in one blob.
 *
 * GitHub rejects repository files above 100 MB, so larger recordings are left in cloud storage
 * instead of being read into memory and attempted. Shared with the UI so the limit can be shown
 * before a save fails.
 */
public const val GITHUB_ARCHIVE_MAX_MEDIA_BYTES: Long = 100L * 1024 * 1024
