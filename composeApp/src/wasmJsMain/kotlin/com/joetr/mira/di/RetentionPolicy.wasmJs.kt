package com.joetr.mira.di

import com.joetr.mira.data.entries.media.RetentionPolicy

/**
 * The browser keeps no local media.
 *
 * Not a preference: OPFS quota is a fraction of free disk and is evicted without warning, so a
 * "local copy" here could vanish silently. Media always streams from Storage.
 */
internal actual fun defaultRetentionPolicy(): RetentionPolicy = RetentionPolicy.StreamOnly
