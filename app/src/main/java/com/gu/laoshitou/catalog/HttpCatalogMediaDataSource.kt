// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.catalog

import android.media.MediaDataSource
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Random-access [MediaDataSource] for the PC SQL library API.
 *
 * FFmpeg runs with --disable-network, so every seek reopens the HTTP stream
 * with a Range header. The PC API authenticates each request with a bearer
 * token; the PC serves the local catalog file directly.
 */
class HttpCatalogMediaDataSource(
    private val url: String,
    private val token: String,
    private val size: Long,
) : MediaDataSource() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var stream: InputStream? = null
    private var response: Response? = null
    private var position = 0L

    override fun readAt(requestPosition: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        synchronized(this) {
            if (size <= 0) {
                return 0
            }
            if (requestPosition < 0L || requestPosition >= this.size) {
                return -1
            }
            try {
                var input = stream ?: openAt(0L)
                if (requestPosition < position) {
                    closeStream()
                    input = openAt(0L)
                }
                if (requestPosition > position) {
                    var remaining = requestPosition - position
                    while (remaining > 0L) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0L) {
                            closeStream()
                            input = openAt(requestPosition)
                            break
                        }
                        remaining -= skipped
                    }
                    position = requestPosition
                }
                val wanted = minOf(size.toLong(), this.size - requestPosition).toInt()
                val read = input.read(buffer, offset, wanted)
                if (read > 0) {
                    position += read
                }
                read
            } catch (e: Exception) {
                Log.e(LOG_TAG, "readAt pos=$requestPosition failed: ${e.message}", e)
                throw e
            }
        }

    override fun getSize(): Long = size

    override fun close() {
        synchronized(this) { closeStream() }
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun openAt(offset: Long): InputStream {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Range", "bytes=$offset-")
            .build()
        val newResponse = client.newCall(request).execute()
        if (!newResponse.isSuccessful && newResponse.code != 206) {
            val code = newResponse.code
            newResponse.close()
            throw IOException("HTTP $code")
        }
        val body = newResponse.body
        if (body == null) {
            newResponse.close()
            throw IOException("empty response")
        }
        response = newResponse
        position = offset
        return body.byteStream().also { stream = it }
    }

    private fun closeStream() {
        try {
            stream?.close()
        } catch (_: Exception) {
        }
        try {
            response?.close()
        } catch (_: Exception) {
        }
        stream = null
        response = null
        position = 0L
    }

    private companion object {
        const val LOG_TAG = "PcCatalogSource"
    }
}
