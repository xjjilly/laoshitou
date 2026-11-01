// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.util.Log

/**
 * DMR backend that prefers the native DSP pipeline and transparently falls
 * back to MediaPlayer when the native decoder cannot start a stream (for
 * example DFF, or a format MediaPlayer supports but the custom AVIO path
 * cannot probe).
 */
internal class DspDmrBackend(
    context: Context,
    dsp: DmrDspConfig,
) : DmrPlaybackBackend {

    private val native = NativeDmrBackend(context, dsp)
    private val mediaPlayer = MediaPlayerBackend(context)
    private var active: DmrPlaybackBackend = native
    private var lastUri: String? = null
    private var fallingBack = false
    private var volume = 50
    private var muted = false

    override var listener: MediaPlayerBackend.Listener? = null
        set(value) {
            field = value
            native.listener = nativeListener
            mediaPlayer.listener = mediaPlayerListener
        }

    private val nativeListener = MediaPlayerBackend.Listener { snapshot ->
        if (active === native) {
            if (snapshot.state == MediaPlayerBackend.State.ERROR && !fallingBack) {
                fallingBack = true
                active = mediaPlayer
                Log.w(TAG, "DMR 原生播放失败，回退 MediaPlayer: ${snapshot.error}")
                mediaPlayer.setVolume(volume)
                mediaPlayer.setMute(muted)
                lastUri?.let { mediaPlayer.play(it) }
                listener?.onStateChanged(
                    MediaPlayerBackend.Snapshot(
                        MediaPlayerBackend.State.TRANSITIONING,
                        0L,
                        0L,
                        lastUri,
                        snapshot.error,
                    ),
                )
            } else {
                listener?.onStateChanged(snapshot)
            }
        }
    }

    private val mediaPlayerListener = MediaPlayerBackend.Listener { snapshot ->
        if (active === mediaPlayer) {
            listener?.onStateChanged(snapshot)
        }
    }

    override fun snapshot(): MediaPlayerBackend.Snapshot = active.snapshot()

    override fun play(uri: String) {
        lastUri = uri
        fallingBack = false
        active = native
        native.setVolume(volume)
        native.setMute(muted)
        native.play(uri)
    }

    override fun pause() = active.pause()

    override fun resume() = active.resume()

    override fun stop() {
        native.stop()
        mediaPlayer.stop()
        active = native
    }

    override fun seekTo(position: Long) = active.seekTo(position)

    override fun setVolume(value: Int) {
        volume = value.coerceIn(0, 100)
        native.setVolume(volume)
        mediaPlayer.setVolume(volume)
    }

    override fun setMute(value: Boolean) {
        muted = value
        native.setMute(muted)
        mediaPlayer.setMute(muted)
    }

    override fun release() {
        native.release()
        mediaPlayer.release()
    }

    private companion object {
        const val TAG = "M0Dlna"
    }
}
