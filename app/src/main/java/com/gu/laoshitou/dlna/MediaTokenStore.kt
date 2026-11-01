// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Per-DMS-session token whitelist. Tokens are 128-bit URL-safe strings. */
internal class MediaTokenStore {

    private val random = SecureRandom()
    private val tokens = ConcurrentHashMap<String, DmsTrack>()
    private val tokenByTrack = ConcurrentHashMap<String, String>()

    fun tokenFor(track: DmsTrack): String {
        tokenByTrack[track.id]?.let { return it }
        synchronized(this) {
            tokenByTrack[track.id]?.let { return it }
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            tokens[token] = track
            tokenByTrack[track.id] = token
            return token
        }
    }

    fun trackFor(token: String): DmsTrack? = tokens[token]

    fun clear() {
        tokens.clear()
        tokenByTrack.clear()
    }
}
