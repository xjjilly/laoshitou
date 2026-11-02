// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * Streams a byte range from a WebDAV GET.
 *
 * CyberLink's `HTTPRequest.post()` seeks by calling `InputStream.skip(start)`.
 * A plain cursor increment would silently download the file from offset 0 and
 * throw the bytes away, so `skip` closes the current connection and the next
 * read reopens the GET with a new `Range: bytes=<position>-` header.
 *
 * The stream is lazy: constructing it and calling `skip` before the first read
 * performs no network I/O. Servers that ignore Range and answer `200 OK` are
 * handled by discarding bytes up to the requested position.
 */
internal class WebDavRangeInputStream(
    private val client: WebDavClient,
    private val url: HttpUrl,
    private val rangeStart: Long,
    private val rangeEndInclusive: Long,
) : InputStream() {
    private var position = rangeStart
    private var response: Response? = null
    private var body: InputStream? = null
    private var closed = false

    override fun read(): Int {
        if (position > rangeEndInclusive) {
            closeConnection()
            return -1
        }
        val input = ensureOpen() ?: return -1
        val value = input.read()
        if (value < 0) {
            position = rangeEndInclusive + 1
            closeConnection()
            return -1
        }
        position++
        if (position > rangeEndInclusive) {
            closeConnection()
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > b.size) {
            throw IndexOutOfBoundsException()
        }
        if (len == 0) {
            return 0
        }
        if (position > rangeEndInclusive) {
            closeConnection()
            return -1
        }
        val input = ensureOpen() ?: return -1
        val max = if (rangeEndInclusive == Long.MAX_VALUE) {
            len
        } else {
            minOf(len.toLong(), rangeEndInclusive - position + 1).toInt()
        }
        val count = input.read(b, off, max)
        if (count > 0) {
            position += count
            if (position > rangeEndInclusive) {
                closeConnection()
            }
        } else if (count < 0) {
            position = rangeEndInclusive + 1
            closeConnection()
        }
        return count
    }

    override fun skip(n: Long): Long {
        if (closed) {
            throw IOException("stream closed")
        }
        if (n <= 0L) {
            return 0L
        }
        val remaining = remainingBytes()
        if (remaining <= 0L) {
            return 0L
        }
        val skipped = if (remaining == Long.MAX_VALUE) n else minOf(n, remaining)
        position += skipped
        // Force the next read to reopen with a fresh Range header. This is the
        // whole point of this stream: never download-then-discard.
        closeConnection()
        return skipped
    }

    override fun available(): Int = 0

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        closeConnection()
    }

    private fun remainingBytes(): Long =
        if (rangeEndInclusive == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            (rangeEndInclusive - position + 1).coerceAtLeast(0L)
        }

    private fun ensureOpen(): InputStream? {
        if (closed) {
            throw IOException("stream closed")
        }
        body?.let { return it }
        if (position > rangeEndInclusive) {
            return null
        }
        val end = if (rangeEndInclusive == Long.MAX_VALUE) null else rangeEndInclusive
        val resp = client.openRange(url, position, end)
        response = resp
        if (resp.code == HTTP_RANGE_NOT_SATISFIABLE) {
            closeConnection()
            position = rangeEndInclusive + 1
            return null
        }
        if (resp.code != HTTP_PARTIAL_CONTENT && resp.code != HTTP_OK) {
            closeConnection()
            throw IOException("WebDAV GET failed: HTTP ${resp.code}")
        }
        val input = resp.body?.byteStream() ?: run {
            closeConnection()
            throw IOException("WebDAV GET returned an empty body")
        }
        val serverStart = if (resp.code == HTTP_PARTIAL_CONTENT) {
            contentRangeStart(resp.header("Content-Range")) ?: position
        } else {
            0L
        }
        val discard = position - serverStart
        if (discard < 0L) {
            closeConnection()
            throw IOException("WebDAV returned an unexpected range start")
        }
        if (discard > 0L) {
            skipFully(input, discard)
        }
        body = input
        return input
    }

    private fun closeConnection() {
        body = null
        val current = response
        response = null
        try {
            current?.close()
        } catch (_: Exception) {
            // The renderer may have disconnected first.
        }
    }

    private fun contentRangeStart(value: String?): Long? {
        if (value == null) {
            return null
        }
        val spec = value.substringAfter("bytes", "").trim()
        return spec.substringBefore('-').trim().toLongOrNull()
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(DISCARD_BUFFER_SIZE)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) {
                throw IOException("WebDAV response ended before the requested range")
            }
            remaining -= read
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val DISCARD_BUFFER_SIZE = 8192
    }
}
