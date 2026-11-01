// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import java.io.File

/** Debug-only DMS library with one generated album/track. */
internal class DebugDmsLibrary(context: Context) : DmsLibrary {

    private val album = DmsAlbum("a0", "测试音频")
    private val track: DmsTrack

    init {
        val directory = File(context.cacheDir, "dms").apply { mkdirs() }
        val file = File(directory, "tone-30s.wav")
        if (!file.isFile || file.length() == 0L) {
            file.writeBytes(DebugRangeServer.testToneWav(seconds = 30))
        }
        track = DmsTrack(
            id = "a0-t0",
            title = "测试音 30 秒",
            artist = null,
            albumId = album.id,
            album = album.title,
            file = file,
        )
    }

    override val rootTitle: String = "老石头 DMS 测试"

    override fun albums(): List<DmsAlbum> = listOf(album)

    override fun tracks(albumId: String): List<DmsTrack> =
        if (albumId == album.id) listOf(track) else emptyList()

    override fun findAlbum(albumId: String): DmsAlbum? =
        if (albumId == album.id) album else null

    override fun findTrack(trackId: String): DmsTrack? =
        if (trackId == track.id) track else null
}
