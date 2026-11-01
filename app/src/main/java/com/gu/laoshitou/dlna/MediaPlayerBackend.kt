// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * DMR playback backend. MediaPlayer is created and controlled on the main
 * thread; the LAN preflight/HTTP source is prepared on an IO thread.
 *
 * Every URI goes through [VettedMediaDataSource], so MediaPlayer never sees a
 * URL and cannot follow a redirect outside the LAN.
 */
internal class MediaPlayerBackend(context: Context) : DmrPlaybackBackend {

    enum class State {
        STOPPED,
        PLAYING,
        PAUSED,
        TRANSITIONING,
        ERROR,
    }

    data class Snapshot(
        val state: State,
        val positionMs: Long,
        val durationMs: Long,
        val uri: String?,
        val error: String?,
    )

    fun interface Listener {
        fun onStateChanged(snapshot: Snapshot)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var source: VettedMediaDataSource? = null
    private var currentUri: String? = null
    private var state = State.STOPPED
    private var positionMs = 0L
    private var durationMs = 0L
    private var errorMessage: String? = null
    private var volume = 50
    private var muted = false
    private var session = 0
    private var focusRequest: AudioFocusRequest? = null

    private val ticker = object : Runnable {
        override fun run() {
            val active = synchronized(lock) {
                if (state == State.PLAYING) {
                    try {
                        positionMs = player?.currentPosition?.toLong() ?: positionMs
                    } catch (_: IllegalStateException) {
                        // Player is being released.
                    }
                    true
                } else {
                    false
                }
            }
            if (active) {
                mainHandler.postDelayed(this, 1_000)
            }
        }
    }

    override var listener: Listener? = null

    override fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(state, positionMs, durationMs, currentUri, errorMessage)
    }

    override fun play(uri: String) {
        mainHandler.post { beginPlay(uri) }
    }

    override fun pause() {
        mainHandler.post {
            if (synchronized(lock) { state } == State.PLAYING) {
                player?.pause()
                synchronized(lock) { state = State.PAUSED }
                stopTicker()
                notifyState()
            }
        }
    }

    override fun resume() {
        mainHandler.post {
            val current = synchronized(lock) { state }
            if (current == State.PAUSED) {
                player?.start()
                synchronized(lock) { state = State.PLAYING }
                startTicker()
                notifyState()
            } else if (current == State.STOPPED || current == State.ERROR) {
                synchronized(lock) { currentUri }?.let { beginPlay(it) }
            }
        }
    }

    override fun stop() {
        mainHandler.post {
            synchronized(lock) {
                session += 1
                state = State.STOPPED
                positionMs = 0L
                errorMessage = null
            }
            stopTicker()
            releasePlayer()
            abandonFocus()
            notifyState()
        }
    }

    override fun seekTo(position: Long) {
        mainHandler.post {
            player?.let {
                try {
                    it.seekTo(position, MediaPlayer.SEEK_CLOSEST)
                    synchronized(lock) { positionMs = position }
                } catch (_: IllegalStateException) {
                    // Player is not prepared.
                }
            }
        }
    }

    override fun setVolume(value: Int) {
        mainHandler.post {
            synchronized(lock) { volume = value.coerceIn(0, 100) }
            applyVolume()
        }
    }

    override fun setMute(value: Boolean) {
        mainHandler.post {
            synchronized(lock) { muted = value }
            applyVolume()
        }
    }

    override fun release() {
        mainHandler.post {
            synchronized(lock) { session += 1; state = State.STOPPED }
            stopTicker()
            releasePlayer()
            abandonFocus()
        }
        ioExecutor.shutdownNow()
    }

    private fun beginPlay(uri: String) {
        val mySession = synchronized(lock) {
            session += 1
            currentUri = uri
            state = State.TRANSITIONING
            positionMs = 0L
            durationMs = 0L
            errorMessage = null
            session
        }
        stopTicker()
        releasePlayer()
        notifyState()

        ioExecutor.execute {
            try {
                val dataSource = VettedMediaDataSource(uri)
                dataSource.prepare()
                mainHandler.post {
                    if (isCurrent(mySession)) {
                        attachPlayer(dataSource, mySession)
                    } else {
                        dataSource.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DMR 数据源准备失败: $uri", e)
                mainHandler.post {
                    if (isCurrent(mySession)) {
                        synchronized(lock) {
                            state = State.ERROR
                            errorMessage = e.message ?: e.javaClass.simpleName
                        }
                        notifyState()
                    }
                }
            }
        }
    }

    private fun attachPlayer(dataSource: VettedMediaDataSource, mySession: Int) {
        if (!isCurrent(mySession)) {
            dataSource.close()
            return
        }
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        source = dataSource
        try {
            mediaPlayer.setAudioAttributes(audioAttributes)
            applyVolume(mediaPlayer)
            mediaPlayer.setDataSource(dataSource)
            mediaPlayer.setOnPreparedListener { prepared ->
                if (!isCurrent(mySession)) {
                    return@setOnPreparedListener
                }
                synchronized(lock) {
                    durationMs = prepared.duration.toLong()
                    state = State.PLAYING
                }
                requestFocus()
                prepared.start()
                startTicker()
                notifyState()
            }
            mediaPlayer.setOnCompletionListener {
                if (!isCurrent(mySession)) {
                    return@setOnCompletionListener
                }
                stopTicker()
                synchronized(lock) {
                    state = State.STOPPED
                    positionMs = durationMs
                }
                abandonFocus()
                notifyState()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                if (isCurrent(mySession)) {
                    stopTicker()
                    synchronized(lock) {
                        state = State.ERROR
                        errorMessage = "what=$what extra=$extra"
                    }
                    abandonFocus()
                    notifyState()
                }
                true
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "DMR MediaPlayer 启动失败", e)
            synchronized(lock) {
                state = State.ERROR
                errorMessage = e.message ?: e.javaClass.simpleName
            }
            releasePlayer()
            notifyState()
        }
    }

    private fun releasePlayer() {
        player?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
            } catch (_: IllegalStateException) {
                // Player was already released or not prepared.
            }
            it.release()
        }
        player = null
        source?.close()
        source = null
    }

    private fun applyVolume() {
        synchronized(lock) {
            player?.let { applyVolume(it) }
        }
    }

    private fun applyVolume(target: MediaPlayer) {
        val value = synchronized(lock) { if (muted) 0f else volume / 100f }
        target.setVolume(value, value)
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, 1_000)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(ticker)
    }

    private fun requestFocus() {
        if (focusRequest != null || audioManager == null) {
            return
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    pause()
                }
            }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun isCurrent(mySession: Int): Boolean = synchronized(lock) { session == mySession }

    private fun notifyState() {
        val snapshot = snapshot()
        Log.i(TAG, "DMR 状态: ${snapshot.state} pos=${snapshot.positionMs}/${snapshot.durationMs} err=${snapshot.error}")
        listener?.onStateChanged(snapshot)
    }

    private companion object {
        const val TAG = "M0Dlna"
    }
}
