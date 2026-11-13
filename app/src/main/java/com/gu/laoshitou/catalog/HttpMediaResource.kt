// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.catalog

import com.gu.laoshitou.media.MediaResource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * A PC SQL library track exposed through the shared [MediaResource] contract.
 * The phone's DMS pulls byte ranges from the PC API and forwards them to the
 * renderer, so the renderer only ever sees the phone's media URL.
 */
class HttpMediaResource(
    private val url: String,
    private val token: String,
    override val name: String,
    override val length: Long,
    override val mime: String?,
) : MediaResource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun openRange(start: Long, endInclusive: Long): InputStream {
        if (start < 0L || endInclusive < start) {
            return ByteArrayInputStream(ByteArray(0))
        }
        val last = if (length > 0L) minOf(endInclusive, length - 1) else endInclusive
        if (start > last) {
            return ByteArrayInputStream(ByteArray(0))
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Range", "bytes=$start-$last")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            return ByteArrayInputStream(ByteArray(0))
        }
        val body = response.body
        if (body == null) {
            response.close()
            return ByteArrayInputStream(ByteArray(0))
        }
        return object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
