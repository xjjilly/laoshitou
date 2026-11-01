// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.media.MediaDataSource

/**
 * The only network input accepted by the DMR MediaPlayer.
 *
 * MediaPlayer never receives a URL: it reads bytes from this source, and
 * every byte comes from [HttpRangeSource], which enforces the LAN-only URL
 * policy and validates every redirect hop.
 */
class VettedMediaDataSource internal constructor(
    private val source: HttpRangeSource,
) : MediaDataSource() {

    constructor(url: String) : this(HttpRangeSource(url))

    /** Preflights size/range support on an IO thread before playback. */
    fun prepare() = source.prepare()

    override fun getSize(): Long = source.getSize()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        source.readAt(position, buffer, offset, size)

    override fun close() = source.close()
}
