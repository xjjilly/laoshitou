// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gu.laoshitou.dlna.NetworkGate

/**
 * Tracks one LAN connection session. [sessionKey] changes when the phone joins
 * a different Wi-Fi/Ethernet network, which lets the player reset DLNA state.
 */
class LanSessionController(context: Context) {

    var sessionKey by mutableStateOf<String?>(null)
        private set

    private val gate = NetworkGate(
        context = context,
        onLanSession = { key, _ ->
            if (key != sessionKey) {
                sessionKey = key
            }
        },
        onLanLost = { sessionKey = null },
    )

    fun start() = gate.start()

    fun stop() = gate.stop()
}
