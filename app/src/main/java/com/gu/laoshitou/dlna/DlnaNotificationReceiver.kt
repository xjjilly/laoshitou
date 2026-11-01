// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gu.laoshitou.ui.DlnaMediaSession

/** Routes the DLNA notification transport buttons to the shared media session. */
class DlnaNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val session = DlnaMediaSession.current() ?: return
        when (intent.action) {
            ACTION_PLAY -> session.onPlay?.invoke()
            ACTION_PAUSE -> session.onPause?.invoke()
            ACTION_STOP -> session.onStop?.invoke()
        }
    }

    companion object {
        const val ACTION_PLAY = "com.gu.laoshitou.dlna.PLAY"
        const val ACTION_PAUSE = "com.gu.laoshitou.dlna.PAUSE"
        const val ACTION_STOP = "com.gu.laoshitou.dlna.STOP"
    }
}
