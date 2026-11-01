// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gu.laoshitou.NativeBridge
import com.gu.laoshitou.queryNativeOutputPlan
import com.gu.laoshitou.ui.EqEditorFrequencies
import java.util.Locale
import java.util.concurrent.Executors

/**
 * DMR playback through the native pipeline:
 * FFmpeg (LAN HTTP via VettedMediaDataSource) -> app DSP chain -> Oboe.
 *
 * The source is still [VettedMediaDataSource], so the LAN-only URL policy and
 * redirect checks stay in Kotlin; FFmpeg itself is built with --disable-network.
 */
internal class NativeDmrBackend(
    context: Context,
    private val dsp: DmrDspConfig,
) : DmrPlaybackBackend {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var state = MediaPlayerBackend.State.STOPPED
    private var positionMs = 0L
    private var durationMs = 0L
    private var currentUri: String? = null
    private var errorMessage: String? = null
    private var session = 0
    private var volume = 50
    private var muted = false
    private var tickerRunning = false
    private var monitorTicks = 0

    override var listener: MediaPlayerBackend.Listener? = null

    override fun snapshot(): MediaPlayerBackend.Snapshot = synchronized(lock) {
        MediaPlayerBackend.Snapshot(state, positionMs, durationMs, currentUri, errorMessage)
    }

    override fun play(uri: String) {
        mainHandler.post {
            val mySession = synchronized(lock) {
                session += 1
                currentUri = uri
                state = MediaPlayerBackend.State.TRANSITIONING
                positionMs = 0L
                durationMs = 0L
                errorMessage = null
                session
            }
            stopTicker()
            NativeBridge.stopPlayback()
            notifyState()

            ioExecutor.execute {
                try {
                    val source = VettedMediaDataSource(uri)
                    source.prepare()
                    val plan = queryNativeOutputPlan(appContext)
                    val started = NativeBridge.startSource(
                        source,
                        dsp.processing,
                        plan.supportedRates,
                        plan.nativeRate,
                        dsp.eqPath,
                        dsp.crossfeed,
                        dsp.eqCascade,
                        dsp.eqUseSlope,
                        dsp.eqSlope,
                        dsp.forcedOutputRate,
                        dsp.resamplerQuality,
                        dsp.crossfeedWidth,
                        dsp.stereoWidth,
                        dsp.loudnessEnabled,
                        volume / 100.0,
                        dsp.limiterEnabled,
                        dsp.limiterThresholdDb,
                        plan.usbDetected,
                        dsp.autoUsb192k,
                        dsp.brirPath,
                        dsp.brirEnabled,
                        dsp.brirMix,
                        dsp.eqLinearPhase,
                        dsp.multibandEnabled,
                        dsp.multibandLowHz,
                        dsp.multibandHighHz,
                        dsp.multibandThresholdDb,
                    )
                    mainHandler.post {
                        if (!isCurrent(mySession)) {
                            return@post
                        }
                        if (!started) {
                            synchronized(lock) {
                                state = MediaPlayerBackend.State.ERROR
                                errorMessage = NativeBridge.playbackLastError()
                                    .ifBlank { "原生 DSP 播放启动失败" }
                            }
                            notifyState()
                            return@post
                        }
                        applyVolume()
                        applyStoredVisualEq()
                        synchronized(lock) {
                            state = MediaPlayerBackend.State.PLAYING
                            durationMs = NativeBridge.playbackDurationMs()
                            positionMs = 0L
                        }
                        startTicker()
                        notifyState()
                        Log.i(
                            TAG,
                            "DMR 原生 DSP: processing=${NativeBridge.playbackProcessingMode()} " +
                                "eq=${NativeBridge.playbackEqBandCount()} " +
                                "crossfeed=${NativeBridge.playbackCrossfeedEnabled()} " +
                                "limiter=${NativeBridge.playbackLimiterEnabled()} " +
                                "brir=${NativeBridge.playbackBrirActive()}",
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DMR 原生播放失败: $uri", e)
                    mainHandler.post {
                        if (!isCurrent(mySession)) {
                            return@post
                        }
                        synchronized(lock) {
                            state = MediaPlayerBackend.State.ERROR
                            errorMessage = e.message ?: e.javaClass.simpleName
                        }
                        notifyState()
                    }
                }
            }
        }
    }

    override fun pause() {
        mainHandler.post {
            if (synchronized(lock) { state } == MediaPlayerBackend.State.PLAYING) {
                NativeBridge.pausePlayback()
                synchronized(lock) { state = MediaPlayerBackend.State.PAUSED }
                notifyState()
            }
        }
    }

    override fun resume() {
        mainHandler.post {
            when (synchronized(lock) { state }) {
                MediaPlayerBackend.State.PAUSED -> {
                    NativeBridge.resumePlayback()
                    synchronized(lock) { state = MediaPlayerBackend.State.PLAYING }
                    startTicker()
                    notifyState()
                }
                MediaPlayerBackend.State.STOPPED,
                MediaPlayerBackend.State.ERROR,
                -> synchronized(lock) { currentUri }?.let { play(it) }
                else -> Unit
            }
        }
    }

    override fun stop() {
        mainHandler.post {
            synchronized(lock) {
                session += 1
                state = MediaPlayerBackend.State.STOPPED
                positionMs = 0L
                errorMessage = null
            }
            stopTicker()
            NativeBridge.stopPlayback()
            notifyState()
        }
    }

    override fun seekTo(position: Long) {
        ioExecutor.execute {
            val current = synchronized(lock) { state }
            if (current != MediaPlayerBackend.State.PLAYING &&
                current != MediaPlayerBackend.State.PAUSED &&
                current != MediaPlayerBackend.State.TRANSITIONING
            ) {
                return@execute
            }
            if (NativeBridge.seekPlayback(position)) {
                // AudioEngine::seek_to_ms re-runs configure_processing with the
                // start-time profile; re-apply the live 16-band visual EQ.
                applyStoredVisualEq()
                mainHandler.post {
                    synchronized(lock) { positionMs = position }
                    notifyState()
                }
            }
        }
    }

    override fun setVolume(value: Int) {
        synchronized(lock) { volume = value.coerceIn(0, 100) }
        mainHandler.post { applyVolume() }
    }

    override fun setMute(value: Boolean) {
        synchronized(lock) { muted = value }
        mainHandler.post { applyVolume() }
    }

    override fun release() {
        mainHandler.post {
            synchronized(lock) {
                session += 1
                state = MediaPlayerBackend.State.STOPPED
            }
            stopTicker()
            NativeBridge.stopPlayback()
        }
        ioExecutor.shutdownNow()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val active = synchronized(lock) {
                if (state != MediaPlayerBackend.State.PLAYING &&
                    state != MediaPlayerBackend.State.PAUSED
                ) {
                    tickerRunning = false
                    false
                } else if (NativeBridge.playbackEnded()) {
                    durationMs = NativeBridge.playbackDurationMs()
                    positionMs = durationMs
                    state = MediaPlayerBackend.State.STOPPED
                    tickerRunning = false
                    NativeBridge.stopPlayback()
                    true
                } else {
                    durationMs = NativeBridge.playbackDurationMs()
                    positionMs = NativeBridge.playbackPositionMs()
                    state = if (NativeBridge.isPlaybackPaused()) {
                        MediaPlayerBackend.State.PAUSED
                    } else {
                        MediaPlayerBackend.State.PLAYING
                    }
                    true
                }
            }
            if (active) {
                notifyState()
                monitorTicks += 1
                if (monitorTicks % 10 == 0) {
                    Log.i(
                        TAG,
                        "DMR 监控: latency=" +
                            String.format(Locale.US, "%.1f", NativeBridge.playbackLatencyMs()) +
                            "ms underruns=${NativeBridge.playbackUnderruns()} " +
                            "xruns=${NativeBridge.playbackXruns()} " +
                            "eq=${NativeBridge.playbackEqBandCount()} " +
                            "pos=${NativeBridge.playbackPositionMs()} " +
                            "state=${snapshot().state}",
                    )
                }
                if (synchronized(lock) { tickerRunning }) {
                    mainHandler.postDelayed(this, 1_000)
                }
            }
        }
    }

    private fun startTicker() {
        if (synchronized(lock) { tickerRunning }) {
            return
        }
        synchronized(lock) { tickerRunning = true }
        mainHandler.post(ticker)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(ticker)
        synchronized(lock) { tickerRunning = false }
    }

    private fun applyVolume() {
        val currentVolume = synchronized(lock) { volume }
        val currentMuted = synchronized(lock) { muted }
        NativeBridge.setPlaybackGain(if (currentMuted) 0f else currentVolume / 100f)
        NativeBridge.setLoudnessVolume(currentVolume / 100f)
    }

    /**
     * Re-applies the live 16-band visual EQ saved by MainActivity. Needed
     * after start and after every seek, because seek re-runs the start-time
     * DSP profile. The UI's live apply path calls NativeBridge.updateEq
     * directly while the DMR is running.
     */
    private fun applyStoredVisualEq() {
        val prefs = appContext.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("use_visual_eq", false)) {
            return
        }
        val gains = prefs.getString("eq_editor_gains", null)
            ?.split(',')
            ?.mapNotNull { it.trim().toFloatOrNull() }
            ?: return
        if (gains.size != EqEditorFrequencies.size) {
            return
        }
        val preamp = prefs.getFloat("eq_editor_preamp", 0f).toDouble()
        NativeBridge.updateEq(
            gains.toFloatArray(),
            EqEditorFrequencies.map { it.toFloat() }.toFloatArray(),
            preamp,
        )
    }

    private fun isCurrent(mySession: Int): Boolean = synchronized(lock) { session == mySession }

    private fun notifyState() {
        listener?.onStateChanged(snapshot())
    }

    private companion object {
        const val TAG = "M0Dlna"
    }
}
