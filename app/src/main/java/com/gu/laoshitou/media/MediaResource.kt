// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.media

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * A byte source that can be served over DLNA. Local files and remote WebDAV
 * entries implement the same contract so the DMS layer does not care where
 * the bytes live.
 */
interface MediaResource {
    val name: String
    val length: Long
    val mime: String?

    /**
     * Opens the inclusive byte range [start, endInclusive]. Callers must close
     * the returned stream.
     */
    fun openRange(start: Long, endInclusive: Long): InputStream

    fun openStream(): InputStream {
        val end = if (length > 0) length - 1 else Long.MAX_VALUE
        return openRange(0, end)
    }
}

/** A local file backed [MediaResource]. */
class LocalFileResource(
    private val file: File,
    override val mime: String? = null,
) : MediaResource {
    override val name: String get() = file.name
    override val length: Long get() = file.length()

    override fun openRange(start: Long, endInclusive: Long): InputStream {
        if (!file.isFile || start < 0 || endInclusive < start) {
            return ByteArrayInputStream(ByteArray(0))
        }
        val input = FileInputStream(file)
        var skipped = 0L
        try {
            while (skipped < start) {
                val step = input.skip(start - skipped)
                if (step <= 0L) {
                    return ByteArrayInputStream(ByteArray(0))
                }
                skipped += step
            }
        } catch (e: Exception) {
            input.close()
            throw e
        }
        return LimitedInputStream(input, endInclusive - start + 1)
    }
}

private class LimitedInputStream(
    input: InputStream,
    private var remaining: Long,
) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0L) {
            return -1
        }
        val value = super.read()
        if (value >= 0) {
            remaining--
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) {
            return -1
        }
        val count = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (count > 0) {
            remaining -= count
        }
        return count
    }

    override fun skip(n: Long): Long {
        if (n <= 0L || remaining <= 0L) {
            return 0L
        }
        val skipped = super.skip(minOf(n, remaining))
        if (skipped > 0L) {
            remaining -= skipped
        }
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
}
