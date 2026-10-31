package com.joetr.mira.di

import com.joetr.mira.data.entries.media.RetentionPolicy

/** Disk is cheap here, so desktop holds the complete offline archive. */
internal actual fun defaultRetentionPolicy(): RetentionPolicy = RetentionPolicy.KeepAll
