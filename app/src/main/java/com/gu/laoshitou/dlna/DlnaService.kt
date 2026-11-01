// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.gu.laoshitou.MainActivity
import com.gu.laoshitou.ui.DlnaMediaSession

/**
 * Foreground service for active DLNA sessions. DMR receiving and DMC casting
 * both play/pull media, so the notification uses mediaPlayback type and the
 * shared [DlnaMediaSession]; the service owns nothing else and stops as soon
 * as the session becomes inactive.
 */
class DlnaService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> show(intent)
        }
        return START_NOT_STICKY
    }

    private fun show(intent: Intent?) {
        val session = DlnaMediaSession.current()
        if (session == null) {
            stopSelf()
            return
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty {
            getString(com.gu.laoshitou.R.string.app_name)
        }
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        ensureChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playPending = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, DlnaNotificationReceiver::class.java)
                .setAction(DlnaNotificationReceiver.ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pausePending = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, DlnaNotificationReceiver::class.java)
                .setAction(DlnaNotificationReceiver.ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPending = PendingIntent.getBroadcast(
            this,
            3,
            Intent(this, DlnaNotificationReceiver::class.java)
                .setAction(DlnaNotificationReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.token())
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_play, "播放", playPending,
            ).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "暂停", pausePending,
            ).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending,
            ).build())
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground 失败: ${e.message}")
            stopSelf()
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "DLNA 播放",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "接收投送与投送控制的播放通知"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val TAG = "M0Dlna"
        private const val CHANNEL_ID = "dlna_playback"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_HIDE = "com.gu.laoshitou.dlna.HIDE"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        /** Shows/updates the FGS notification, or stops the service when inactive. */
        fun showNotification(context: Context, title: String?, text: String, active: Boolean) {
            val appContext = context.applicationContext
            if (!active) {
                hideNotification(appContext)
                return
            }
            val intent = Intent(appContext, DlnaService::class.java)
                .putExtra(EXTRA_TITLE, title.orEmpty())
                .putExtra(EXTRA_TEXT, text)
            try {
                appContext.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "启动 DlnaService 失败: ${e.message}")
            }
        }

        fun hideNotification(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, DlnaService::class.java))
        }
    }
}
