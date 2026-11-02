// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.util.Log
import com.gu.laoshitou.media.LocalFileResource
import com.gu.laoshitou.media.MediaResource
import org.cybergarage.http.HTTPRequest
import org.cybergarage.net.HostInterface
import org.cybergarage.upnp.Device
import org.cybergarage.upnp.UPnP
import org.cybergarage.upnp.device.InvalidDescriptionException
import org.cybergarage.upnp.std.av.server.ConnectionManager
import org.cybergarage.upnp.std.av.server.ContentDirectory
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * MediaServer:1 device with a custom description and a token/Range media
 * endpoint hosted by CyberLink's HTTP server. The LAN address is resolved at
 * start time; a Wi-Fi change requires a new session.
 */
internal class DmsDevice(
    context: Context,
    private val library: DmsLibrary,
) : Device() {

    private val appContext = context.applicationContext
    private val udn = DlnaUdn.forRole(appContext, "dms")
    private val tokenStore = MediaTokenStore()
    private val castSequence = AtomicLong()

    private var mediaEndpoint: DmsMediaEndpoint? = null
    private var contentDirectory: DmsContentDirectory? = null
    private var boundAddress: String? = null
    private var httpPort = DEFAULT_HTTP_PORT
    private var started = false

    var descriptionUrl: String? = null
        private set

    @Synchronized
    fun startDevice(port: Int = DEFAULT_HTTP_PORT): Boolean {
        if (started) {
            return true
        }
        val local = NetworkAddresses.findLan(appContext)
        if (local == null) {
            Log.e(TAG, "DMS 启动失败：没有 Wi-Fi/以太网私有 IPv4")
            return false
        }
        val address = local.address.hostAddress ?: return false

        CyberLinkBootstrap.ensureInitialized()
        UPnP.setEnable(UPnP.USE_ONLY_IPV4_ADDR)
        HostInterface.setInterface(address)

        try {
            val description = DmsDescription.build(udn)
            val loaded = loadDescription(description)
            Log.i(TAG, "DMS 描述装载: loaded=$loaded deviceNode=${getDeviceNode()?.name}")
            if (!loaded) {
                Log.e(TAG, "DMS 描述装载返回 false")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "DMS 描述解析失败", e)
            return false
        }
        val cdService = getService(ContentDirectory.SERVICE_TYPE)
        val cmService = getService(ConnectionManager.SERVICE_TYPE)
        if (cdService == null || cmService == null) {
            Log.e(TAG, "DMS 描述缺少服务")
            return false
        }
        cdService.loadSCPD(ContentDirectory.SCPD)
        cmService.loadSCPD(ConnectionManager.SCPD)

        boundAddress = address
        httpPort = port
        mediaEndpoint = DmsMediaEndpoint(tokenStore)
        val cd = DmsContentDirectory(library, tokenStore) {
            boundAddress?.let { "http://$it:$httpPort" }
        }
        contentDirectory = cd
        cdService.setActionListener(cd)
        cdService.setQueryListener(cd)

        val cm = DmsConnectionManager()
        cmService.setActionListener(cm)
        cmService.setQueryListener(cm)

        setHTTPPort(port)
        val ok = try {
            super.start()
        } catch (e: Exception) {
            Log.e(TAG, "DMS 启动异常", e)
            false
        }
        if (!ok) {
            Log.e(TAG, "DMS start() 返回 false")
            mediaEndpoint = null
            contentDirectory = null
            tokenStore.clear()
            try {
                super.stop()
            } catch (_: Exception) {
                // Best-effort cleanup after a failed start.
            }
            return false
        }

        httpPort = getHTTPPort()
        started = true
        descriptionUrl = "http://$address:$httpPort/description.xml"
        Log.i(TAG, "DMS 已启动: $descriptionUrl")
        return true
    }

    /**
     * Returns a token URL for one local file served by this DMS session.
     * Used by the DMC casting path; requires the DMS media endpoint to be
     * running so the renderer can pull the file from this phone.
     */
    @Synchronized
    fun mediaUrl(file: File): String? = mediaUrl(LocalFileResource(file))

    /**
     * Returns a token URL for any [MediaResource]. The renderer only sees this
     * phone's DMS URL, so WebDAV credentials never leave the device.
     */
    @Synchronized
    fun mediaUrl(resource: MediaResource): String? {
        if (!started || resource.length <= 0L) {
            return null
        }
        val host = boundAddress ?: return null
        val info = MediaProtocolInfo.forFileName(resource.name)
        val mime = info?.mime ?: resource.mime
        val track = DmsTrack(
            id = "cast-${castSequence.incrementAndGet()}",
            title = resource.name.substringBeforeLast('.', resource.name),
            artist = null,
            albumId = "",
            album = null,
            resource = resource,
        )
        val token = tokenStore.tokenFor(track)
        val fileName = MediaProtocolInfo.mediaFileName(track.id, resource.name, mime)
        return "http://$host:$httpPort${DmsMediaEndpoint.PREFIX}$token/$fileName"
    }

    override fun httpRequestRecieved(httpReq: HTTPRequest) {
        val uri = httpReq.getURI().orEmpty()
        if (uri.startsWith(DmsMediaEndpoint.PREFIX)) {
            if (mediaEndpoint?.handle(httpReq) != true) {
                httpReq.returnBadRequest()
            }
            return
        }
        super.httpRequestRecieved(httpReq)
    }

    @Synchronized
    fun shutdown() {
        if (!started) {
            return
        }
        started = false
        mediaEndpoint = null
        contentDirectory = null
        tokenStore.clear()
        try {
            super.stop()
            Log.i(TAG, "DMS 已停止")
        } catch (e: Exception) {
            Log.w(TAG, "DMS 停止失败: ${e.message}")
        }
        descriptionUrl = null
    }

    private companion object {
        const val TAG = "M0Dlna"
        const val DEFAULT_HTTP_PORT = 39520
    }
}
