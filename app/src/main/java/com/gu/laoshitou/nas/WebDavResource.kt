// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import com.gu.laoshitou.media.MediaResource
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A WebDAV entry exposed through the shared [MediaResource] contract. The
 * owning client keeps the session credentials; renderers only ever see the
 * phone's DMS URL.
 */
internal class WebDavResource(
    private val client: WebDavClient,
    val entry: WebDavEntry,
) : MediaResource {
    override val name: String get() = entry.name

    override val length: Long get() = entry.size ?: -1L

    override val mime: String?
        get() = entry.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: WebDavMedia.mimeForExtension(entry.extension.orEmpty())

    override fun openRange(start: Long, endInclusive: Long): InputStream {
        if (start < 0L || endInclusive < start) {
            return ByteArrayInputStream(ByteArray(0))
        }
        val last = if (length > 0L) minOf(endInclusive, length - 1) else endInclusive
        if (start > last) {
            return ByteArrayInputStream(ByteArray(0))
        }
        return WebDavRangeInputStream(client, entry.url, start, last)
    }
}
