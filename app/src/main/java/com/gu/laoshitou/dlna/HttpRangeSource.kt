// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Byte source for a single HTTP media file on the LAN.
 *
 * Every connection, including each redirect hop, is checked by [UrlPolicy].
 * The class is deliberately small and synchronous: MediaPlayer calls
 * [readAt] from a native thread, and [readAt] reopens the HTTP connection
 * only when the requested position goes backwards or jumps.
 */
internal class HttpRangeSource(
    rawUrl: String,
    private val policy: UrlPolicy = UrlPolicy.DEFAULT,
) : Closeable {

    private val initialUri: URI = policy.requireAllowed(rawUrl)

    private var closed = false
    private var size = UNKNOWN_SIZE
    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var streamPosition = -1L

    /** Performs the HEAD/Range preflight without waiting for MediaPlayer. */
    @Synchronized
    fun prepare() {
        ensurePrepared()
    }

    @Synchronized
    fun getSize(): Long {
        ensurePrepared()
        return size
    }

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) {
            throw IOException("数据源已关闭")
        }
        if (position < 0 || offset < 0 || size < 0 || offset + size > buffer.size) {
            throw IndexOutOfBoundsException("readAt 参数越界")
        }
        if (size == 0) {
            return 0
        }
        ensurePrepared()
        if (position >= this.size) {
            return -1
        }
        if (connection == null || streamPosition != position) {
            openAt(position)
        }

        val input = stream ?: throw IOException("数据流未打开")
        var total = 0
        while (total < size) {
            val n = input.read(buffer, offset + total, size - total)
            if (n <= 0) {
                break
            }
            total += n
            streamPosition += n
        }
        return if (total == 0) -1 else total
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        closeConnection()
    }

    private fun ensurePrepared() {
        if (size >= 0) {
            return
        }
        if (closed) {
            throw IOException("数据源已关闭")
        }
        preflight()
    }

    private fun preflight() {
        val head = open(initialUri, "HEAD", null)
        try {
            val code = head.responseCode
            if (code in 200..299) {
                val length = head.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
                if (length != null && length > 0) {
                    size = length
                    return
                }
            }
        } finally {
            head.disconnect()
        }

        // Some DLNA servers reject HEAD; probe with a one-byte Range instead.
        val probe = open(initialUri, "GET", "bytes=0-0")
        try {
            val code = probe.responseCode
            when (code) {
                206 -> {
                    val total = parseContentRange(probe.getHeaderField("Content-Range"))?.second
                    if (total != null && total > 0) {
                        size = total
                        return
                    }
                }
                200 -> {
                    val length = probe.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
                    if (length != null && length > 0) {
                        size = length
                        return
                    }
                }
            }
            throw IOException("无法确定媒体大小（HTTP $code）")
        } finally {
            closeQuietly(probe.errorStream)
            probe.disconnect()
        }
    }

    private fun openAt(position: Long) {
        closeConnection()
        val conn = open(initialUri, "GET", "bytes=$position-")
        val code = conn.responseCode
        when (code) {
            206 -> {
                val range = parseContentRange(conn.getHeaderField("Content-Range"))
                    ?: throw IOException("服务器未返回有效的 Content-Range")
                if (range.first != position) {
                    conn.disconnect()
                    throw IOException("服务器返回的 Range 起点不一致")
                }
                val total = range.second
                if (total != null && total > 0) {
                    size = total
                }
                stream = BufferedInputStream(conn.inputStream, BUFFER_SIZE)
            }
            200 -> {
                // Server ignored Range; skip forward so the caller still gets
                // the requested offset.
                stream = BufferedInputStream(conn.inputStream, BUFFER_SIZE)
                skipFully(stream!!, position)
            }
            else -> {
                closeQuietly(conn.errorStream)
                conn.disconnect()
                throw IOException("HTTP $code")
            }
        }
        connection = conn
        streamPosition = position
    }

    private fun open(uri: URI, method: String, range: String?): HttpURLConnection {
        var current = uri
        var redirects = 0
        while (true) {
            val conn = (current.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                requestMethod = method
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
                if (range != null) {
                    setRequestProperty("Range", range)
                }
            }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            if (code in 300..399) {
                if (redirects >= UrlGuard.MAX_REDIRECTS) {
                    conn.disconnect()
                    throw IOException("重定向次数过多")
                }
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("重定向缺少 Location")
                }
                current = policy.resolveRedirect(current, location.trim())
                redirects++
                continue
            }
            return conn
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) {
                throw IOException("服务器未返回请求的起始位置")
            }
            remaining -= n
        }
    }

    private fun closeConnection() {
        closeQuietly(stream)
        stream = null
        connection?.disconnect()
        connection = null
        streamPosition = -1L
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: IOException) {
            // Nothing useful to do while tearing down a media source.
        }
    }

    companion object {
        private const val UNKNOWN_SIZE = -1L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val USER_AGENT = "LaoShiTou/DLNA"

        /**
         * Parses `Content-Range: bytes start-end/total` and returns
         * `start to total` (`total` is null for `*`).
         */
        private fun parseContentRange(value: String?): Pair<Long, Long?>? {
            if (value == null) {
                return null
            }
            val parts = value.trim().split(' ', limit = 2)
            if (parts.size != 2 || !parts[0].equals("bytes", ignoreCase = true)) {
                return null
            }
            val rangeAndTotal = parts[1].split('/', limit = 2)
            if (rangeAndTotal.size != 2) {
                return null
            }
            val start = rangeAndTotal[0].substringBefore('-').toLongOrNull() ?: return null
            val total = rangeAndTotal[1].trim().let { if (it == "*") null else it.toLongOrNull() }
            return start to total
        }
    }
}
