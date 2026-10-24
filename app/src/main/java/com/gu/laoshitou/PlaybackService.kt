// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiverRegistered = false
    private var title = "老石头"
    private var lastPaused = false
    private var resumeOnFocusGain = false
    private var stopping = false
    private var endedAtMs = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pauseFromService()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = true
                pauseFromService()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                NativeBridge.setPlaybackGain(0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                NativeBridge.setPlaybackGain(1.0f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resumeFromService()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseFromService()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        mediaSession = MediaSession(this, "LaoshitouPlayback")
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                resumeFromService()
            }

            override fun onPause() {
                pauseFromService()
            }

            override fun onStop() {
                stopPlaybackInternal()
            }
        })
        mediaSession.isActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                title = intent.getStringExtra(EXTRA_TITLE) ?: "老石头"
                lastPaused = false
                stopping = false
                endedAtMs = 0L
                updateSession(false)
                startForegroundCompat(buildNotification(false))
                requestFocus()
                registerNoisyReceiver()
                acquireWakeLock()
                startMonitor()
            }
            ACTION_PAUSE -> pauseFromService()
            ACTION_RESUME -> resumeFromService()
            ACTION_STOP -> stopPlaybackInternal()
            else -> {
                if (!NativeBridge.isPlaying()) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        unregisterNoisyReceiver()
        abandonFocus()
        releaseWakeLock()
        mediaSession.isActive = false
        mediaSession.release()
        NativeBridge.stopPlayback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(500)
                if (!NativeBridge.isPlaying()) {
                    stopPlaybackInternal()
                    break
                }
                // Give the UI a few seconds to start the next playlist track
                // before tearing the foreground service down.
                if (NativeBridge.playbackEnded()) {
                    if (endedAtMs == 0L) {
                        endedAtMs = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - endedAtMs >= 3000L) {
                        stopPlaybackInternal()
                        break
                    }
                } else {
                    endedAtMs = 0L
                }
                // Oboe reports a closed stream after a route change (USB DAC,
                // Bluetooth, wired headset). Reopen before the monitor decides
                // the playback has stopped.
                if (NativeBridge.playbackNeedsRecovery()) {
                    android.util.Log.w("PlaybackService", "音频输出被系统断开，正在重开")
                    if (!NativeBridge.recoverPlayback()) {
                        android.util.Log.e("PlaybackService", "重开音频输出失败，停止播放")
                        stopPlaybackInternal()
                        break
                    }
                }
                val paused = NativeBridge.isPlaybackPaused()
                if (paused != lastPaused) {
                    lastPaused = paused
                    updateNotification(paused)
                    updateSession(paused)
                }
            }
        }
    }

    private fun pauseFromService() {
        NativeBridge.pausePlayback()
        lastPaused = true
        updateNotification(true)
        updateSession(true)
    }

    private fun resumeFromService() {
        NativeBridge.resumePlayback()
        lastPaused = false
        updateNotification(false)
        updateSession(false)
    }

    private fun stopPlaybackInternal() {
        if (stopping) return
        stopping = true
        monitorJob?.cancel()
        NativeBridge.stopPlayback()
        abandonFocus()
        unregisterNoisyReceiver()
        releaseWakeLock()
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleAction = if (paused) {
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_play),
                "继续",
                servicePendingIntent(ACTION_RESUME, 1),
            ).build()
        } else {
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "暂停",
                servicePendingIntent(ACTION_PAUSE, 1),
            ).build()
        }
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "停止",
            servicePendingIntent(ACTION_STOP, 2),
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(if (paused) "已暂停" else "正在播放")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun updateNotification(paused: Boolean) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(paused))
    }

    private fun updateSession(paused: Boolean) {
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP,
            )
            .setState(
                if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1.0f,
            )
            .build()
        mediaSession.setPlaybackState(state)
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .build(),
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "播放控制",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun requestFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            pauseFromService()
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyReceiverRegistered = false
        }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Laoshitou:Playback",
            )
            wakeLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "laoshitou_playback"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "extra_title"
        private const val ACTION_START = "com.gu.laoshitou.action.START"
        private const val ACTION_PAUSE = "com.gu.laoshitou.action.PAUSE"
        private const val ACTION_RESUME = "com.gu.laoshitou.action.RESUME"
        private const val ACTION_STOP = "com.gu.laoshitou.action.STOP"

        fun start(context: Context, title: String) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
