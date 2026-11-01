// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gu.laoshitou.dlna.NetworkGate

/**
 * Tracks one LAN connection session. Every new session raises
 * [rolePromptOpen]; the user must choose a role (or offline) before DLNA can
 * start. Losing/changing the network closes the prompt and resets the choice.
 */
class LanSessionController(context: Context) {

    var address by mutableStateOf<String?>(null)
        private set
    var sessionKey by mutableStateOf<String?>(null)
        private set
    var rolePromptOpen by mutableStateOf(false)
        private set

    private var roleChosenForSession = false

    private val gate = NetworkGate(
        context = context,
        onLanSession = { key, host ->
            if (key != sessionKey) {
                sessionKey = key
                address = host
                roleChosenForSession = false
            }
            if (!roleChosenForSession) {
                rolePromptOpen = true
            }
        },
        onLanLost = {
            sessionKey = null
            address = null
            roleChosenForSession = false
            rolePromptOpen = false
        },
    )

    fun start() = gate.start()

    fun stop() = gate.stop()

    fun markRoleChosen() {
        roleChosenForSession = true
        rolePromptOpen = false
    }
}
