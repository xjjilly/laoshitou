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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.gu.laoshitou.MainActivity

/**
 * Keeps the process alive while the sender role exposes its media library.
 * The DMS itself is owned by [com.gu.laoshitou.ui.DlnaController]; this
 * service only carries the specialUse foreground notification so Android
 * does not reclaim the process while another device browses or streams.
 */
class DmsService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("老石头 · 曲库共享中")
            .setContentText("仅局域网内设备可浏览并播放曲库，点击管理")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Background-start restrictions (Android 12+): DMS still runs
            // while the app is in the foreground, just without FGS protection.
            Log.w(TAG, "DMS 前台服务启动失败: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "DLNA 曲库共享",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "发送者角色下的曲库共享状态"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val TAG = "M0Dlna"
        private const val CHANNEL_ID = "dlna_dms"
        private const val NOTIFICATION_ID = 42

        fun show(context: Context) {
            val appContext = context.applicationContext
            try {
                appContext.startForegroundService(Intent(appContext, DmsService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "启动 DmsService 失败: ${e.message}")
            }
        }

        fun hide(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, DmsService::class.java))
        }
    }
}
