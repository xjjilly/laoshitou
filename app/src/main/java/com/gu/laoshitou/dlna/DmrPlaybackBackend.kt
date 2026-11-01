// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

/**
 * Playback backend used by the DMR AVTransport/RenderingControl handlers.
 *
 * Two implementations exist: [MediaPlayerBackend] (compatibility fallback)
 * and [NativeDmrBackend] (FFmpeg -> app DSP chain -> Oboe).
 */
internal interface DmrPlaybackBackend {
    var listener: MediaPlayerBackend.Listener?

    fun snapshot(): MediaPlayerBackend.Snapshot

    fun play(uri: String)

    fun pause()

    fun resume()

    fun stop()

    fun seekTo(position: Long)

    fun setVolume(value: Int)

    fun setMute(value: Boolean)

    fun release()
}
