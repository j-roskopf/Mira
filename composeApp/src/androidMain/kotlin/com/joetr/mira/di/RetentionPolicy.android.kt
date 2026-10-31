package com.joetr.mira.di

import com.joetr.mira.data.entries.media.RetentionPolicy

/** 5 GB by default, overridable in Settings. Anything evicted re-streams on demand. */
internal actual fun defaultRetentionPolicy(): RetentionPolicy =
    RetentionPolicy.Capped(5L * 1024 * 1024 * 1024)
