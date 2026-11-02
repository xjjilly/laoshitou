// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import com.gu.laoshitou.media.LocalFileResource
import com.gu.laoshitou.scanMusicLibrary
import java.io.File

/**
 * DMS browse model over the existing folder-based MusicLibrary. The library
 * is scanned once at DMS start; a later refresh creates a new instance so
 * object IDs stay stable for the session.
 */
internal class MusicLibraryDmsLibrary(root: File) : DmsLibrary {

    private val albumList: List<DmsAlbum>
    private val tracksByAlbum: Map<String, List<DmsTrack>>
    private val albumsById: Map<String, DmsAlbum>
    private val tracksById: Map<String, DmsTrack>

    init {
        val albums = mutableListOf<DmsAlbum>()
        val tracksByAlbum = mutableMapOf<String, List<DmsTrack>>()
        val tracksById = mutableMapOf<String, DmsTrack>()
        scanMusicLibrary(root).forEachIndexed { albumIndex, folder ->
            val album = DmsAlbum(
                id = "a$albumIndex",
                title = folder.name,
            )
            albums += album
            val tracks = folder.files.mapIndexed { trackIndex, file ->
                DmsTrack(
                    id = "a$albumIndex-t$trackIndex",
                    title = file.nameWithoutExtension,
                    artist = null,
                    albumId = album.id,
                    album = folder.name,
                    resource = LocalFileResource(file),
                )
            }
            tracksByAlbum[album.id] = tracks
            tracks.forEach { tracksById[it.id] = it }
        }
        albumList = albums
        this.tracksByAlbum = tracksByAlbum
        this.tracksById = tracksById
        albumsById = albums.associateBy { it.id }
    }

    override val rootTitle: String = "老石头曲库"

    override fun albums(): List<DmsAlbum> = albumList

    override fun tracks(albumId: String): List<DmsTrack> = tracksByAlbum[albumId].orEmpty()

    override fun findAlbum(albumId: String): DmsAlbum? = albumsById[albumId]

    override fun findTrack(trackId: String): DmsTrack? = tracksById[trackId]
}
