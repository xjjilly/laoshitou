// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.catalog

data class CatalogAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int,
    val trackCount: Int,
    val coverPath: String,
)

data class CatalogTrack(
    val id: String,
    val albumId: String,
    val title: String,
    val artist: String,
    val format: String,
    val filePath: String,
    val fileSize: Long,
    val durationMs: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val dsdRate: Int,
)

data class CatalogAlbumDetail(
    val album: CatalogAlbum,
    val folderPath: String,
    val tracks: List<CatalogTrack>,
)
