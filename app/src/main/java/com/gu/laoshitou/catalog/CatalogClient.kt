// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Read-only client for the NAS SQL library API (FastAPI, port 9007).
 * The API token is sent as a bearer header on every request.
 */
class CatalogClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun listAlbums(): List<CatalogAlbum> = withContext(Dispatchers.IO) {
        val array = JSONArray(get("/api/v1/albums?limit=500"))
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(albumFrom(it)) }
            }
        }
    }

    suspend fun getAlbum(id: String): CatalogAlbumDetail = withContext(Dispatchers.IO) {
        val objectValue = JSONObject(get("/api/v1/albums/${urlSegment(id)}"))
        val trackArray = objectValue.optJSONArray("tracks") ?: JSONArray()
        val tracks = buildList {
            for (index in 0 until trackArray.length()) {
                trackArray.optJSONObject(index)?.let { add(trackFrom(it)) }
            }
        }
        CatalogAlbumDetail(
            album = albumFrom(objectValue),
            folderPath = string(objectValue, "folder_path"),
            tracks = tracks,
        )
    }

    suspend fun loadCover(album: CatalogAlbum): ByteArray? = withContext(Dispatchers.IO) {
        val path = album.coverPath
        if (path.isEmpty()) {
            return@withContext null
        }
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            base + path
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    response.body?.bytes()
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun get(path: String): String {
        val url = (base + path).toHttpUrlOrNull()
            ?: throw CatalogException("无效的 API 地址")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw CatalogException("服务器返回 HTTP ${response.code}")
                }
                return response.body?.string() ?: throw CatalogException("服务器返回空响应")
            }
        } catch (e: CatalogException) {
            throw e
        } catch (e: IOException) {
            throw CatalogException(e.message ?: "网络错误", e)
        }
    }

    private fun albumFrom(objectValue: JSONObject): CatalogAlbum = CatalogAlbum(
        id = string(objectValue, "id"),
        title = string(objectValue, "title"),
        artist = string(objectValue, "artist"),
        year = objectValue.optInt("year"),
        trackCount = objectValue.optInt("track_count"),
        coverPath = string(objectValue, "cover_url"),
    )

    private fun trackFrom(objectValue: JSONObject): CatalogTrack = CatalogTrack(
        id = string(objectValue, "id"),
        albumId = string(objectValue, "album_id"),
        title = string(objectValue, "title"),
        artist = string(objectValue, "artist"),
        format = string(objectValue, "format"),
        filePath = string(objectValue, "file_path"),
        fileSize = objectValue.optLong("file_size"),
        durationMs = objectValue.optInt("duration_ms"),
        sampleRate = objectValue.optInt("sample_rate"),
        bitDepth = objectValue.optInt("bit_depth"),
        dsdRate = objectValue.optInt("dsd_rate"),
    )

    private fun string(objectValue: JSONObject, name: String): String =
        if (objectValue.isNull(name)) "" else objectValue.optString(name)

    private fun urlSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
