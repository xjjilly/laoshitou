// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

/**
 * DMS browse model used when the local music library is not authorised (or is
 * empty). The media server still runs so WebDAV/DMC casting can use its token
 * endpoint; the browsable local library is simply empty.
 */
internal object EmptyDmsLibrary : DmsLibrary {
    override val rootTitle: String = "老石头曲库"

    override fun albums(): List<DmsAlbum> = emptyList()

    override fun tracks(albumId: String): List<DmsTrack> = emptyList()

    override fun findAlbum(albumId: String): DmsAlbum? = null

    override fun findTrack(trackId: String): DmsTrack? = null
}
