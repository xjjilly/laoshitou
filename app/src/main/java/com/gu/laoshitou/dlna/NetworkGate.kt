// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * Detects a physical LAN session (Wi-Fi/Ethernet with a private IPv4) and
 * reports exactly one callback per new address/interface pair. Lost/removed
 * networks report [onLanLost]. Callbacks are delivered on the main thread.
 */
internal class NetworkGate(
    context: Context,
    private val onLanSession: (key: String, address: String) -> Unit,
    private val onLanLost: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastKey: String? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = evaluate()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            evaluate()

        override fun onLost(network: Network) = evaluate()
    }

    fun start() {
        connectivity?.registerDefaultNetworkCallback(callback)
        evaluate()
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        lastKey = null
    }

    private fun evaluate() {
        val lan = NetworkAddresses.findLan(appContext)
        val host = lan?.address?.hostAddress
        val key = if (lan != null && host != null) {
            "${lan.networkInterface.name}:$host"
        } else {
            null
        }
        mainHandler.post {
            if (key != null && host != null) {
                if (key != lastKey) {
                    lastKey = key
                    onLanSession(key, host)
                }
            } else if (lastKey != null) {
                lastKey = null
                onLanLost()
            }
        }
    }
}
