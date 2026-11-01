// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

internal data class LocalNetwork(
    val address: Inet4Address,
    val networkInterface: NetworkInterface,
)

internal object NetworkAddresses {

    /**
     * Returns the physical LAN address only. Cellular and VPN-only networks
     * are deliberately rejected: DLNA must never run there.
     */
    fun findLan(context: Context): LocalNetwork? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isWifi && !isEthernet) {
            return null
        }
        val linkProperties = manager.getLinkProperties(network) ?: return null
        val address = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: return null
        val networkInterface = try {
            NetworkInterface.getByInetAddress(address)
        } catch (_: SocketException) {
            null
        } ?: return null
        return LocalNetwork(address, networkInterface)
    }
}
