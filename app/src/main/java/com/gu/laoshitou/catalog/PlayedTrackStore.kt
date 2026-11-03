// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.catalog

import android.content.Context

/** Remembers which SQL library tracks have been played at least once. */
class PlayedTrackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY_PLAYED, emptySet())?.toSet() ?: emptySet()

    fun markPlayed(trackId: String) {
        if (trackId.isEmpty()) {
            return
        }
        prefs.edit().putStringSet(KEY_PLAYED, load() + trackId).apply()
    }

    private companion object {
        const val PREFS = "catalog_played"
        const val KEY_PLAYED = "played_track_ids"
    }
}
