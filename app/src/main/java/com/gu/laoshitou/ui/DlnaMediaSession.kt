// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import com.gu.laoshitou.dlna.DlnaService

/**
 * Process-wide system media session for DLNA playback. DMR sessions expose
 * the received track; DMC sessions expose the cast target with transport
 * controls. The session appears in system media controls and the lock
 * screen; [DlnaService] shows the matching foreground-service notification
 * so playback survives while the process stays alive.
 */
internal class DlnaMediaSession private constructor(context: Context) {

    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    private val appContext = context.applicationContext
    private val session = MediaSession(appContext, TAG)

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                onPlay?.invoke()
            }

            override fun onPause() {
                onPause?.invoke()
            }

            override fun onStop() {
                onStop?.invoke()
            }

            override fun onSeekTo(pos: Long) {
                onSeek?.invoke(pos)
            }

            override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                // Route hardware/Bluetooth media keys without a registered
                // MediaButtonReceiver (some OEMs deliver keys this way).
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(
                    android.content.Intent.EXTRA_KEY_EVENT,
                )
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            onPlay?.invoke()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            onPause?.invoke()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            onStop?.invoke()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
    }

    fun token(): MediaSession.Token = session.sessionToken

    companion object {
        private const val TAG = "LaoshitouDlna"

        @Volatile
        private var instance: DlnaMediaSession? = null

        fun getOrCreate(context: Context): DlnaMediaSession =
            instance ?: synchronized(this) {
                instance ?: DlnaMediaSession(context.applicationContext).also { instance = it }
            }

        fun current(): DlnaMediaSession? = instance

        fun forget(mediaSession: DlnaMediaSession) {
            synchronized(this) {
                if (instance === mediaSession) {
                    instance = null
                }
            }
        }
    }

    fun updateDmr(
        state: String,
        title: String?,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        active: Boolean,
    ) {
        val playbackState = when (state) {
            "PLAYING" -> PlaybackState.STATE_PLAYING
            "PAUSED_PLAYBACK" -> PlaybackState.STATE_PAUSED
            "TRANSITIONING" -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_STOPPED
        }
        val builder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO,
            )
            .setState(playbackState, positionMs, if (playing) 1f else 0f)
        if (durationMs > 0) {
            builder.setBufferedPosition(durationMs)
        }
        session.setPlaybackState(builder.build())
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "接收投送")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "DLNA · DSP")
                .build(),
        )
        // Keep the FGS alive for the whole DMR device lifetime, not only while
        // a track is playing: the control point must still reach us after a
        // track ends or while paused/stopped.
        session.isActive = active
        DlnaService.showNotification(
            appContext,
            title = title ?: "接收投送",
            text = if (playbackState == PlaybackState.STATE_PLAYING) {
                "DLNA · DSP"
            } else {
                "DMR 待机 · DSP"
            },
            active = active,
        )
    }

    fun updateDmc(
        state: String,
        target: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        paused: Boolean,
        active: Boolean,
    ) {
        val playbackState = when (state) {
            "PLAYING" -> if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
            "PAUSED_PLAYBACK" -> PlaybackState.STATE_PAUSED
            "TRANSITIONING" -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_STOPPED
        }
        val builder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO,
            )
            .setState(playbackState, positionMs, if (playbackState == PlaybackState.STATE_PLAYING) 1f else 0f)
        if (durationMs > 0) {
            builder.setBufferedPosition(durationMs)
        }
        session.setPlaybackState(builder.build())
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "投送到 $target")
                .build(),
        )
        session.isActive = active
        DlnaService.showNotification(
            appContext,
            title = title,
            text = "投送到 $target",
            active = active,
        )
    }

    fun clear() {
        session.isActive = false
        session.setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build(),
        )
        session.setMetadata(null)
        DlnaService.showNotification(appContext, null, "", active = false)
    }

    fun release() {
        session.isActive = false
        session.release()
        DlnaService.hideNotification(appContext)
        forget(this)
    }
}
