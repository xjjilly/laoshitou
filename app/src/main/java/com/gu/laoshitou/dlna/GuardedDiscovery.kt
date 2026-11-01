// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Thin SSDP discovery layer used instead of CyberLink's ControlPoint.
 *
 * CyberLink's ControlPoint fetches a device description as soon as it sees
 * an SSDP response, without validating the LOCATION URL. Fetching is kept
 * out of this class: it only returns devices whose LOCATION already passed
 * [UrlPolicy]. The description is fetched later by the caller with the same
 * policy.
 */
internal class GuardedDiscovery(
    private val context: Context,
    private val policy: UrlPolicy = UrlPolicy.DEFAULT,
) {

    data class FoundDevice(
        val location: URI,
        val st: String,
        val usn: String,
        val server: String,
    )

    @Throws(IOException::class)
    fun discover(timeoutMs: Long = 5_000): List<FoundDevice> {
        val local = NetworkAddresses.findLan(context)
            ?: throw IOException("未连接 Wi-Fi/以太网局域网")

        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val multicastLock = wifiManager?.createMulticastLock("laoshitou-ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        val socket = MulticastSocket(InetSocketAddress(local.address, 0))
        try {
            socket.networkInterface = local.networkInterface
            socket.timeToLive = 1
            socket.soTimeout = 1_000

            val group = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT)
            val requests = listOf(
                searchRequest("urn:schemas-upnp-org:device:MediaRenderer:1"),
                searchRequest("urn:schemas-upnp-org:device:MediaServer:1"),
                searchRequest("ssdp:all"),
            )
            for (request in requests) {
                val bytes = request.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, group))
                Thread.sleep(50)
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8192)
            val found = LinkedHashMap<String, FoundDevice>()
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                val device = parse(text) ?: continue
                found.putIfAbsent("${device.usn}|${device.location}", device)
            }
            Log.i(TAG, "SSDP 发现 ${found.size} 台设备")
            return found.values.toList()
        } finally {
            socket.close()
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
        }
    }

    private fun parse(response: String): FoundDevice? {
        val lines = response.split("\r\n")
        if (lines.isEmpty() || !lines[0].startsWith("HTTP/1.1 200")) {
            return null
        }
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) {
                break
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }
        val locationText = headers["location"] ?: return null
        val location = try {
            policy.requireAllowed(locationText)
        } catch (e: UrlGuardException) {
            Log.w(TAG, "跳过非局域网设备: $locationText (${e.message})")
            return null
        }
        return FoundDevice(
            location = location,
            st = headers["st"].orEmpty(),
            usn = headers["usn"].orEmpty(),
            server = headers["server"].orEmpty(),
        )
    }

    private fun searchRequest(st: String): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: ").append(st).append("\r\n")
        append("USER-AGENT: Android/UPnP LaoShiTou/0.1\r\n")
        append("\r\n")
    }

    private companion object {
        const val TAG = "M0Dlna"
        const val SSDP_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
    }
}
