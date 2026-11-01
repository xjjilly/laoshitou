// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.cybergarage.net.HostInterface
import org.cybergarage.upnp.std.av.renderer.AVTransport
import org.cybergarage.upnp.std.av.renderer.ConnectionManager
import org.cybergarage.upnp.std.av.renderer.MediaRenderer
import org.cybergarage.upnp.std.av.renderer.RenderingControl
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Debug-only M0 harness. It is not part of the release APK.
 *
 * Launch with:
 * adb shell am start -n com.gu.laoshitou/com.gu.laoshitou.dlna.M0Activity \
 *   --ez auto true
 */
class M0Activity : Activity() {

    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var mediaPlayer: MediaPlayer? = null
    private var dataSource: VettedMediaDataSource? = null
    private var rangeServer: DebugRangeServer? = null
    private var renderer: MediaRenderer? = null
    private var dmrDevice: DmrDevice? = null
    private var dmsDevice: DmsDevice? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        append("M0 DLNA 真机验证")
        append(
            "局域网运行时权限：" +
                DlnaPermissions.missing(applicationContext).joinToString().ifEmpty { "已满足" }
        )
        when {
            intent.getBooleanExtra(EXTRA_DMR, false) ->
                handler.postDelayed({ runDmrIntegration() }, 800)
            intent.getBooleanExtra(EXTRA_DMS, false) ->
                handler.postDelayed({ runDmsIntegration() }, 800)
            intent.getBooleanExtra(EXTRA_DMC, false) ->
                handler.postDelayed({ runDmcIntegration() }, 800)
            intent.getBooleanExtra(EXTRA_AUTO, false) ->
                handler.postDelayed({ runAutoSequence() }, 800)
            else ->
                append("点击按钮开始，或使用 --ez auto true / --ez dmr true / --ez dmc true")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        stopRenderer()
        stopDmrIntegration()
        stopDmsIntegration()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        statusView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusView)
        root.addView(button("发现 SSDP 设备") { runDiscovery() })
        root.addView(button("播放 Range 测试音") { runPlaybackTest() })
        root.addView(button("CyberLink MediaRenderer 自检") { runRendererTest() })
        root.addView(button("DMR 联调模式（保持运行）") { runDmrIntegration() })
        root.addView(button("DMS 联调模式（曲库共享）") { runDmsIntegration() })
        root.addView(button("DMC 双机联调（控制另一台）") { runDmcIntegration() })
        root.addView(button("停止") {
            stopPlayback()
            stopRenderer()
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.START
            setOnClickListener { action() }
        }

    private fun runAutoSequence() {
        append("自动序列：发现 → 播放 → 渲染器自检")
        runDiscovery()
        handler.postDelayed({ runPlaybackTest() }, 7_000)
        handler.postDelayed({ runRendererTest() }, 18_000)
        handler.postDelayed({ append("自动序列结束") }, 27_000)
    }

    private fun runDiscovery() {
        append("开始 SSDP 发现（5 秒）…")
        executor.execute {
            try {
                val devices = GuardedDiscovery(applicationContext).discover(5_000)
                if (devices.isEmpty()) {
                    append("SSDP：未发现设备（检查 Wi-Fi、AP 隔离或组播过滤）")
                } else {
                    devices.forEach {
                        append("SSDP：ST=${it.st} LOCATION=${it.location} USN=${it.usn}")
                    }
                }
            } catch (e: Exception) {
                append("SSDP 失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun runPlaybackTest() {
        append("准备本地 Range 测试…")
        executor.execute {
            try {
                val local = NetworkAddresses.findLan(applicationContext)
                    ?: throw IOException("没有 Wi-Fi/以太网私有 IPv4")
                val body = DebugRangeServer.testToneWav()
                val server = DebugRangeServer(local.address, body)
                rangeServer?.close()
                rangeServer = server
                val url = server.start()
                append("测试音频 ${DebugRangeServer.formatSize(body.size)}：$url")

                val source = VettedMediaDataSource(url)
                source.prepare()
                append("受控数据源预检通过：size=${source.getSize()} bytes")
                handler.post { startMediaPlayer(source) }
            } catch (e: Exception) {
                append("播放准备失败：${e.javaClass.simpleName}: ${e.message}")
                handler.post { stopPlayback() }
            }
        }
    }

    private fun startMediaPlayer(source: VettedMediaDataSource) {
        // Release the previous player only. The freshly prepared source and
        // its Range server must stay open until this session ends.
        releasePlayer()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val player = MediaPlayer()
        mediaPlayer = player
        dataSource?.takeIf { it !== source }?.close()
        dataSource = source
        try {
            player.setAudioAttributes(attributes)
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> append("音频焦点变化：$change") }
                .build()
            audioFocusRequest = focus
            val audioManager = getSystemService(AudioManager::class.java)
            append("请求音频焦点：${audioManager.requestAudioFocus(focus)}")
            player.setVolume(0.35f, 0.35f)
            player.setDataSource(source)
            player.setOnPreparedListener { mp ->
                append("MediaPlayer prepared：duration=${mp.duration} ms")
                mp.start()
                append("开始播放：playing=${mp.isPlaying}")
                handler.postDelayed({ logPlaybackState() }, 2_000)
                handler.postDelayed({ logPlaybackState() }, 5_000)
                handler.postDelayed({ stopPlayback() }, 9_000)
            }
            player.setOnCompletionListener {
                append("MediaPlayer 播放完成")
                handler.postDelayed({ stopPlayback() }, 500)
            }
            player.setOnErrorListener { _, what, extra ->
                append("MediaPlayer 错误：what=$what extra=$extra")
                true
            }
            player.setOnInfoListener { _, what, extra ->
                append("MediaPlayer info：what=$what extra=$extra")
                false
            }
            player.prepareAsync()
            append("已 setDataSource(VettedMediaDataSource) + prepareAsync")
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer 启动失败", e)
            append("MediaPlayer 启动失败：${e.javaClass.simpleName}: ${e.message}")
            stopPlayback()
        }
    }

    private fun logPlaybackState() {
        val player = mediaPlayer ?: return
        try {
            append("播放状态：playing=${player.isPlaying} position=${player.currentPosition}/${player.duration}")
        } catch (e: IllegalStateException) {
            append("读取播放状态失败：${e.message}")
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
            } catch (_: IllegalStateException) {
                // Player was already released or in a transient state.
            }
            player.release()
        }
        mediaPlayer = null
        audioFocusRequest?.let {
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)
        }
        audioFocusRequest = null
    }

    private fun stopPlayback() {
        releasePlayer()
        dataSource?.close()
        dataSource = null
        rangeServer?.close()
        rangeServer = null
    }

    private fun runRendererTest() {
        append("启动 CyberLink MediaRenderer 自检…")
        executor.execute {
            try {
                val local = NetworkAddresses.findLan(applicationContext)
                    ?: throw IOException("没有 Wi-Fi/以太网私有 IPv4")
                val ip = local.address.hostAddress ?: throw IOException("没有 IPv4 地址")
                CyberLinkBootstrap.ensureInitialized()
                HostInterface.setInterface(ip)
                val device = MediaRenderer(
                    MediaRenderer.DESCRIPTION,
                    RenderingControl.SCPD,
                    ConnectionManager.SCPD,
                    AVTransport.SCPD,
                )
                device.setInterfaceAddress(ip)
                renderer = device
                val started = device.start()
                append("MediaRenderer start=$started，ip=$ip，port=${device.httpPort}")
                Thread.sleep(2_000)

                val url = "http://$ip:${device.httpPort}/description.xml"
                UrlGuard.requireAllowed(url)
                val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    requestMethod = "GET"
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText().take(240) }.orEmpty()
                append("description HTTP $code：${text.replace('\n', ' ')}")
                connection.disconnect()
                Thread.sleep(1_000)
            } catch (e: Exception) {
                Log.e(TAG, "Renderer 自检失败", e)
                append("Renderer 自检失败：${e.javaClass.simpleName}: ${e.message}")
            } finally {
                stopRenderer()
            }
        }
    }

    private fun runDmrIntegration() {
        append("启动 DMR 联调模式…")
        executor.execute {
            try {
                val local = NetworkAddresses.findLan(applicationContext)
                    ?: throw IOException("没有 Wi-Fi/以太网私有 IPv4")
                val ip = local.address.hostAddress ?: throw IOException("没有 IPv4 地址")
                val body = DebugRangeServer.testToneWav(seconds = 30)
                val server = DebugRangeServer(local.address, body)
                rangeServer?.close()
                rangeServer = server
                val mediaUrl = server.start()

                val dmr = DmrDevice(applicationContext)
                dmrDevice = dmr
                // DmrDevice resolves the current LAN IP itself at start time.
                if (!dmr.start()) {
                    throw IOException("DmrDevice.start() 返回 false")
                }
                append("DMR 联调就绪，保持运行 180 秒")
                append("description: http://$ip:39520/description.xml")
                append("媒体 URL（供主机 SOAP SetAVTransportURI）: $mediaUrl")
                handler.postDelayed({ stopDmrIntegration() }, 180_000)
            } catch (e: Exception) {
                Log.e(TAG, "DMR 联调启动失败", e)
                append("DMR 联调启动失败：${e.javaClass.simpleName}: ${e.message}")
                stopDmrIntegration()
            }
        }
    }

    private fun runDmsIntegration() {
        append("启动 DMS 联调模式…")
        executor.execute {
            try {
                val root = File(Environment.getExternalStorageDirectory(), "laoshitou")
                val realLibrary = MusicLibraryDmsLibrary(root)
                val useRealLibrary = realLibrary.albums().isNotEmpty()
                val library: DmsLibrary = if (useRealLibrary) {
                    realLibrary
                } else {
                    DebugDmsLibrary(applicationContext)
                }
                val dms = DmsDevice(applicationContext, library)
                dmsDevice = dms
                if (!dms.startDevice()) {
                    throw IOException("DmsDevice.startDevice() 返回 false")
                }
                append("DMS 已启动：${dms.descriptionUrl}")
                append("DMS 曲库：${if (useRealLibrary) "真实曲库" else "测试音兜底"}，专辑=${library.albums().size}")
                val firstAlbum = library.albums().firstOrNull()
                val firstTrack = firstAlbum?.let { library.tracks(it.id).firstOrNull() }
                append("示例曲目：${firstAlbum?.title ?: "-"} / ${firstTrack?.title ?: "-"}")
                append("保持运行 180 秒，供控制点 Browse/拉流")
                handler.postDelayed({ stopDmsIntegration() }, 180_000)
            } catch (e: Exception) {
                Log.e(TAG, "DMS 联调启动失败", e)
                append("DMS 联调启动失败：${e.javaClass.simpleName}: ${e.message}")
                stopDmsIntegration()
            }
        }
    }

    private fun stopDmsIntegration() {
        val dms = dmsDevice
        dmsDevice = null
        if (dms != null) {
            append("停止 DMS 联调")
            dms.shutdown()
        }
    }

    private fun runDmcIntegration() {
        append("启动 DMC 双机联调…")
        executor.execute {
            var server: DebugRangeServer? = null
            try {
                val local = NetworkAddresses.findLan(applicationContext)
                    ?: throw IOException("没有 Wi-Fi/以太网私有 IPv4")
                val newServer = DebugRangeServer(
                    local.address,
                    DebugRangeServer.testToneWav(seconds = 30),
                )
                server = newServer
                rangeServer?.close()
                rangeServer = newServer
                val mediaUrl = MediaProtocolInfo.ensureMediaSuffix(
                    URI(newServer.start()),
                    "tone.wav",
                    "audio/wav",
                ).toString()
                append("DMC 媒体源：$mediaUrl")

                val client = DmcClient(applicationContext)
                val renderer = client.findRenderer() ?: throw IOException("未发现 MediaRenderer")
                append("DMC 目标：${renderer.friendlyName} @ ${renderer.location}")
                append("DMC 控制 URL：${renderer.avControlUrl}")

                val set = client.setAvTransportUri(renderer, mediaUrl)
                if (set.code !in 200..299) {
                    val error = DmcClient.extractTag(set.body, "errorDescription")
                    throw IOException("SetAVTransportURI 失败 HTTP ${set.code} $error")
                }
                val play = client.play(renderer)
                if (play.code !in 200..299) {
                    throw IOException("Play 失败 HTTP ${play.code}")
                }
                append("DMC 已发送 SetAVTransportURI + Play")

                Thread.sleep(2_000)
                val info = client.getTransportInfo(renderer)
                append(
                    "DMC 状态：${DmcClient.extractTag(info.body, "CurrentTransportState") ?: "HTTP ${info.code}"}"
                )
                val position = client.getPositionInfo(renderer)
                append(
                    "DMC 进度：${DmcClient.extractTag(position.body, "RelTime") ?: "-"} / " +
                        "${DmcClient.extractTag(position.body, "TrackDuration") ?: "-"}"
                )
                Thread.sleep(6_000)
                val stop = client.stop(renderer)
                append("DMC Stop：HTTP ${stop.code}")
            } catch (e: Exception) {
                Log.e(TAG, "DMC 联调失败", e)
                append("DMC 联调失败：${e.javaClass.simpleName}: ${e.message}")
            } finally {
                server?.close()
                if (rangeServer === server) {
                    rangeServer = null
                }
            }
        }
    }

    private fun stopDmrIntegration() {
        val dmr = dmrDevice
        dmrDevice = null
        val server = rangeServer
        rangeServer = null
        if (dmr != null || server != null) {
            append("停止 DMR 联调")
            dmr?.stop()
            server?.close()
        }
    }

    @Synchronized
    private fun stopRenderer() {
        renderer?.let { device ->
            try {
                device.stop()
                append("MediaRenderer 已停止")
            } catch (e: Exception) {
                append("MediaRenderer 停止失败：${e.message}")
            }
        }
        renderer = null
    }

    private fun append(message: String) {
        Log.i(TAG, message)
        handler.post { statusView.append("$message\n") }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "M0Dlna"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_DMR = "dmr"
        const val EXTRA_DMS = "dms"
        const val EXTRA_DMC = "dmc"
    }
}
