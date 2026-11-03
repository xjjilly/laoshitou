// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.catalog

import android.media.MediaDataSource
import android.util.Log
import com.gu.laoshitou.nas.WebDavClient
import com.gu.laoshitou.nas.WebDavRangeInputStream
import okhttp3.HttpUrl

/**
 * Random-access [MediaDataSource] backed by the saved WebDAV credentials.
 *
 * FFmpeg is built with --disable-network, so the SQL library streams through
 * this Kotlin data source: every seek reopens the GET with a Range header and
 * the DSP pipeline stays byte-for-byte identical to local playback.
 */
class CatalogMediaDataSource(
    private val client: WebDavClient,
    private val url: HttpUrl,
    private val size: Long,
) : MediaDataSource() {
    private val lock = Any()
    private var stream: WebDavRangeInputStream? = null
    private var position = 0L

    override fun readAt(requestPosition: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        synchronized(lock) {
            if (size <= 0) {
                return 0
            }
            if (requestPosition < 0L || requestPosition >= this.size) {
                return -1
            }
            try {
                var input = stream ?: openAt(0L)
                if (requestPosition < position) {
                    input.close()
                    input = openAt(0L)
                }
                if (requestPosition > position) {
                    input.skip(requestPosition - position)
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
        synchronized(lock) {
            stream?.close()
            stream = null
            position = 0L
        }
        client.close()
    }

    private fun openAt(offset: Long): WebDavRangeInputStream {
        position = offset
        val input = WebDavRangeInputStream(client, url, offset, size - 1)
        stream = input
        return input
    }

    private companion object {
        const val LOG_TAG = "CatalogSource"
    }
}
