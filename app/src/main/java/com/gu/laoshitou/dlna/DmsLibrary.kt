// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import com.gu.laoshitou.media.MediaResource

internal data class DmsAlbum(
    val id: String,
    val title: String,
)

internal data class DmsTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val albumId: String,
    val album: String?,
    val resource: MediaResource,
)

/**
 * Read-only browse model for a DMS session. Implementations must be safe to
 * call from CyberLink SOAP threads; the current implementations build an
 * immutable snapshot at construction.
 */
internal interface DmsLibrary {
    val rootTitle: String

    fun albums(): List<DmsAlbum>

    fun tracks(albumId: String): List<DmsTrack>

    fun findAlbum(albumId: String): DmsAlbum?

    fun findTrack(trackId: String): DmsTrack?
}
