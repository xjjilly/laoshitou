// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.util.Log
import org.cybergarage.net.HostInterface
import org.cybergarage.upnp.Device
import org.cybergarage.upnp.UPnP
import org.cybergarage.upnp.device.InvalidDescriptionException
import org.cybergarage.upnp.std.av.renderer.AVTransport
import org.cybergarage.upnp.std.av.renderer.ConnectionManager
import org.cybergarage.upnp.std.av.renderer.RenderingControl

/**
 * MediaRenderer:1 device with a complete description and our own action
 * handlers. CyberLink's bundled MediaRenderer description omits service URLs
 * (and has a MediaRender:1 typo), so this device is built from an explicit
 * description instead.
 */
internal class DmrDevice(
    context: Context,
    private val dsp: DmrDspConfig = DmrDspConfig(),
) {

    private val appContext = context.applicationContext
    private val udn = DlnaUdn.forRole(appContext, "dmr")
    private var device: Device? = null
    private var avTransport: DmrAvTransport? = null
    private var backend: DmrPlaybackBackend? = null

    var descriptionUrl: String? = null
        private set

    @Synchronized
    fun start(port: Int = DEFAULT_HTTP_PORT): Boolean {
        if (device != null) {
            return true
        }
        // Resolve the current LAN address at server-start time. Never cache an
        // address across Wi-Fi changes or pass one in from a stale session.
        val local = NetworkAddresses.findLan(appContext)
        if (local == null) {
            Log.e(TAG, "DMR 启动失败：没有 Wi-Fi/以太网私有 IPv4")
            return false
        }
        val bindAddress = local.address.hostAddress ?: return false
        CyberLinkBootstrap.ensureInitialized()
        UPnP.setEnable(UPnP.USE_ONLY_IPV4_ADDR)
        HostInterface.setInterface(bindAddress)

        val newDevice = Device()
        try {
            newDevice.loadDescription(DmrDescription.build(udn))
        } catch (e: InvalidDescriptionException) {
            Log.e(TAG, "DMR 描述解析失败", e)
            return false
        }

        val avService = newDevice.getService(AVTransport.SERVICE_TYPE)
        val rcService = newDevice.getService(RenderingControl.SERVICE_TYPE)
        val cmService = newDevice.getService(ConnectionManager.SERVICE_TYPE)
        if (avService == null || rcService == null || cmService == null) {
            Log.e(TAG, "DMR 描述缺少服务")
            return false
        }
        avService.loadSCPD(AVTransport.SCPD)
        rcService.loadSCPD(RenderingControl.SCPD)
        cmService.loadSCPD(ConnectionManager.SCPD)

        // CyberLink reads a "sendEvents" attribute, while standard SCPD uses
        // a child element, so LastChange would otherwise never be evented.
        avService.getStateVariable("LastChange")?.setSendEvents(true)
        rcService.getStateVariable("Volume")?.setSendEvents(true)
        rcService.getStateVariable("Mute")?.setSendEvents(true)

        val playerBackend: DmrPlaybackBackend = DspDmrBackend(appContext, dsp)
        val av = DmrAvTransport(avService, playerBackend)
        avService.setActionListener(av)
        avService.setQueryListener(av)

        val renderingControl = DmrRenderingControl(rcService, playerBackend)
        rcService.setActionListener(renderingControl)
        rcService.setQueryListener(renderingControl)

        val connectionManager = DmrConnectionManager()
        cmService.setActionListener(connectionManager)
        cmService.setQueryListener(connectionManager)

        newDevice.setHTTPPort(port)
        val started = try {
            newDevice.start()
        } catch (e: Exception) {
            Log.e(TAG, "DMR 启动失败", e)
            false
        }
        if (!started) {
            Log.e(TAG, "DMR start() 返回 false")
            av.release()
            playerBackend.release()
            newDevice.stop()
            return false
        }

        device = newDevice
        avTransport = av
        backend = playerBackend
        descriptionUrl = "http://$bindAddress:$port/description.xml"
        Log.i(TAG, "DMR 已启动: $descriptionUrl")
        return true
    }

    /** UI/media-session hook for backend state changes. */
    fun setAvStateListener(listener: ((MediaPlayerBackend.Snapshot) -> Unit)?) {
        avTransport?.stateListener = listener
    }

    fun currentDmrTitle(): String? = avTransport?.currentTitle()

    fun playNow(): Boolean = avTransport?.playNow() ?: false

    fun pauseNow(): Boolean = avTransport?.pauseNow() ?: false

    fun stopNow() {
        avTransport?.stopNow()
    }

    fun seekNow(positionMs: Long) {
        avTransport?.seekNow(positionMs)
    }

    @Synchronized
    fun stop() {
        avTransport?.release()
        avTransport = null
        backend?.release()
        backend = null
        device?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "DMR 停止失败: ${e.message}")
            }
        }
        device = null
        descriptionUrl = null
    }

    private companion object {
        const val TAG = "M0Dlna"
        const val DEFAULT_HTTP_PORT = 39520
    }
}
