// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gu.laoshitou.NativeBridge
import com.gu.laoshitou.PlaybackService
import com.gu.laoshitou.dlna.DmcClient
import com.gu.laoshitou.dlna.DmrDevice
import com.gu.laoshitou.dlna.DmrDspConfig
import com.gu.laoshitou.dlna.DmsDevice
import com.gu.laoshitou.dlna.DmsLibrary
import com.gu.laoshitou.dlna.DmsService
import com.gu.laoshitou.dlna.EmptyDmsLibrary
import com.gu.laoshitou.dlna.MediaPlayerBackend
import com.gu.laoshitou.dlna.MediaProtocolInfo
import com.gu.laoshitou.dlna.MusicLibraryDmsLibrary
import com.gu.laoshitou.dlna.NetworkAddresses
import com.gu.laoshitou.media.LocalFileResource
import com.gu.laoshitou.media.MediaResource
import com.gu.laoshitou.nas.WebDavClient
import com.gu.laoshitou.nas.WebDavCredentialStore
import com.gu.laoshitou.nas.WebDavEntry
import com.gu.laoshitou.nas.WebDavResource
import okhttp3.HttpUrl
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DlnaRole(val label: String, val description: String) {
    SENDER(
        "发送者",
        "把手机音乐共享到局域网（DMS），让电视/音箱/另一台手机浏览并拉取曲库。手机不出声。",
    ),
    RECEIVER(
        "接收者",
        "手机作为 DLNA 音箱接收投送。使用原生 FFmpeg + 老石头 DSP 管线（EQ/Crossfeed/BRIR 生效）；不支持的格式自动回退系统播放器。",
    ),
    OFFLINE(
        "保持离线",
        "不使用 DLNA，本次连接零网络行为。",
    ),
}

class DlnaController(private val context: Context) {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("dlna_settings", Context.MODE_PRIVATE)

    var role by mutableStateOf(DlnaRole.OFFLINE)
    var networkAddress by mutableStateOf<String?>(null)

    /** Sender sub-switches, persisted across sessions. */
    var dmsEnabled by mutableStateOf(preferences.getBoolean("dms_enabled", true))
        private set
    var dmcEnabled by mutableStateOf(preferences.getBoolean("dmc_enabled", true))
        private set

    var dmsRunning by mutableStateOf(false)
    var dmsBusy by mutableStateOf(false)
    var dmsStatus by mutableStateOf("")
    var dmsError by mutableStateOf<String?>(null)
    var dmsAlbums by mutableStateOf(0)
    var dmsTracks by mutableStateOf(0)
    var dmsSharesLocalLibrary by mutableStateOf(false)

    var dmrRunning by mutableStateOf(false)
    var dmrBusy by mutableStateOf(false)
    var dmrStatus by mutableStateOf("")
    var dmrError by mutableStateOf<String?>(null)

    var renderers by mutableStateOf<List<DmcClient.Renderer>>(emptyList())
    var selectedRenderer by mutableStateOf<DmcClient.Renderer?>(null)
    var dmcDiscovering by mutableStateOf(false)
    var dmcError by mutableStateOf<String?>(null)
    var casting by mutableStateOf(false)
    var castPaused by mutableStateOf(false)
    var castTitle by mutableStateOf("")
    var castAlbumId by mutableStateOf<String?>(null)
    var castState by mutableStateOf("")
    var castPositionMs by mutableStateOf(0L)
    var castDurationMs by mutableStateOf(0L)
    var castVolume by mutableStateOf(50)

    /** Format mismatch warning: set when the sink list lacks the track MIME. */
    var dmcFormatWarning by mutableStateOf<String?>(null)

    /** True when consecutive control polls fail (renderer powered off/offline). */
    var castOffline by mutableStateOf(false)

    private var dmsDevice: DmsDevice? = null
    private var dmrDevice: DmrDevice? = null
    private val dmcClient = DmcClient(context.applicationContext)
    private val dmcScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var castPollJob: Job? = null
    private data class PendingCast(val resource: MediaResource, val title: String)
    private var pendingCast: PendingCast? = null
    private var castClient: WebDavClient? = null
    private val mediaSession = DlnaMediaSession.getOrCreate(appContext)

    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val wifiLock = wifiManager?.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "laoshitou-dlna",
    )?.apply { setReferenceCounted(false) }
    private val multicastLock = wifiManager?.createMulticastLock("laoshitou-dlna")
        ?.apply { setReferenceCounted(false) }
    private var radioLocksHeld = false

    init {
        mediaSession.onPlay = {
            when (role) {
                DlnaRole.RECEIVER -> dmrDevice?.playNow()
                DlnaRole.SENDER -> dmcScope.launch { pauseOrResumeCast() }
                else -> Unit
            }
        }
        mediaSession.onPause = {
            when (role) {
                DlnaRole.RECEIVER -> dmrDevice?.pauseNow()
                DlnaRole.SENDER -> dmcScope.launch { pauseOrResumeCast() }
                else -> Unit
            }
        }
        mediaSession.onStop = {
            when (role) {
                DlnaRole.RECEIVER -> dmrDevice?.stopNow()
                DlnaRole.SENDER -> dmcScope.launch { stopCast() }
                else -> Unit
            }
        }
        mediaSession.onSeek = { positionMs ->
            when (role) {
                DlnaRole.RECEIVER -> dmrDevice?.seekNow(positionMs)
                DlnaRole.SENDER -> dmcScope.launch { seekCast(positionMs) }
                else -> Unit
            }
        }
    }

    fun refreshNetwork() {
        networkAddress = NetworkAddresses.findLan(context)?.address?.hostAddress
    }

    /**
     * Keep Wi-Fi and multicast alive while any DLNA service is active,
     * including screen-off. Released as soon as the last service stops.
     */
    private fun updateRadioLocks() {
        val needed = dmsRunning || dmrRunning || casting
        if (needed == radioLocksHeld) {
            return
        }
        try {
            if (needed) {
                wifiLock?.acquire()
                multicastLock?.acquire()
            } else {
                multicastLock?.release()
                wifiLock?.release()
            }
            radioLocksHeld = needed
            Log.i(TAG, "DLNA 无线锁: ${if (needed) "已获取" else "已释放"}")
        } catch (e: Exception) {
            Log.w(TAG, "DLNA 无线锁切换失败: ${e.message}")
        }
    }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    suspend fun selectRole(newRole: DlnaRole) {
        if (newRole == role) {
            return
        }
        if (newRole != DlnaRole.SENDER) {
            stopCast()
            stopDms()
        }
        if (newRole != DlnaRole.RECEIVER) {
            stopDmr()
        }
        role = newRole
        // Sender mode means "share this library". Start the DMS right away so
        // the 曲库 is immediately usable for casting without a separate tap.
        if (newRole == DlnaRole.SENDER && dmsEnabled && !dmsRunning && !dmsBusy) {
            startDms()
        }
    }

    suspend fun setDmsEnabled(enabled: Boolean) {
        if (dmsEnabled == enabled) {
            return
        }
        dmsEnabled = enabled
        preferences.edit().putBoolean("dms_enabled", enabled).apply()
        if (!enabled) {
            stopDms()
        }
    }

    suspend fun setDmcEnabled(enabled: Boolean) {
        if (dmcEnabled == enabled) {
            return
        }
        dmcEnabled = enabled
        preferences.edit().putBoolean("dmc_enabled", enabled).apply()
        if (!enabled) {
            stopCast()
            renderers = emptyList()
            selectedRenderer = null
        }
    }

    suspend fun startDms() {
        if (dmsRunning || dmsBusy) {
            return
        }
        dmsBusy = true
        dmsError = null
        try {
            val info = withContext(Dispatchers.IO) {
                val root = File(Environment.getExternalStorageDirectory(), "laoshitou")
                // Do not require all-files access up front: NAS casting only
                // needs the DMS token endpoint. The local library is loaded
                // when the user has granted access and it is non-empty.
                val localLibrary = if (hasAllFilesAccess()) {
                    MusicLibraryDmsLibrary(root).takeIf { it.albums().isNotEmpty() }
                } else {
                    null
                }
                val library: DmsLibrary = localLibrary ?: EmptyDmsLibrary
                val device = DmsDevice(context.applicationContext, library)
                if (!device.startDevice()) {
                    throw IllegalStateException("DMS 启动失败，请检查 Wi-Fi/端口")
                }
                DmsStartInfo(
                    device = device,
                    descriptionUrl = device.descriptionUrl.orEmpty(),
                    albums = library.albums().size,
                    tracks = library.albums().sumOf { library.tracks(it.id).size },
                    localLibrary = localLibrary != null,
                )
            }
            dmsDevice = info.device
            dmsRunning = true
            dmsSharesLocalLibrary = info.localLibrary
            dmsStatus = info.descriptionUrl
            dmsAlbums = info.albums
            dmsTracks = info.tracks
            DmsService.show(appContext)
            updateRadioLocks()
            refreshNetwork()
        } catch (e: Exception) {
            dmsError = e.message ?: e.javaClass.simpleName
            dmsRunning = false
            dmsDevice = null
        } finally {
            dmsBusy = false
        }
    }

    suspend fun stopDms() {
        if (!dmsRunning && dmsDevice == null) {
            return
        }
        if (casting) {
            stopCast()
        }
        dmsBusy = true
        DmsService.hide(appContext)
        val device = dmsDevice
        dmsDevice = null
        withContext(Dispatchers.IO) { device?.shutdown() }
        dmsRunning = false
        dmsSharesLocalLibrary = false
        dmsStatus = ""
        dmsAlbums = 0
        dmsTracks = 0
        dmsBusy = false
        updateRadioLocks()
    }

    /** Restarts DMS after the user grants local-library access. */
    suspend fun reloadDmsWithLocalLibrary() {
        if (!hasAllFilesAccess()) {
            return
        }
        stopDms()
        startDms()
    }

    suspend fun startDmr(dsp: DmrDspConfig = DmrDspConfig()) {
        if (dmrRunning || dmrBusy) {
            return
        }
        dmrBusy = true
        dmrError = null
        try {
            // DMR and local playback must not coexist.
            NativeBridge.stopPlayback()
            context.stopService(Intent(context, PlaybackService::class.java))
            val info = withContext(Dispatchers.IO) {
                val device = DmrDevice(context.applicationContext, dsp)
                if (!device.start()) {
                    throw IllegalStateException("DMR 启动失败，请检查 Wi-Fi/端口")
                }
                DmrStartInfo(device, device.descriptionUrl.orEmpty())
            }
            dmrDevice = info.device
            dmrRunning = true
            dmrStatus = info.descriptionUrl
            updateRadioLocks()
            mediaSession.updateDmr(
                state = "STOPPED",
                title = "等待投送",
                positionMs = 0L,
                durationMs = 0L,
                playing = false,
                active = true,
            )
            info.device.setAvStateListener { snapshot ->
                if (role == DlnaRole.RECEIVER) {
                    val transportState = when (snapshot.state) {
                        MediaPlayerBackend.State.STOPPED -> "STOPPED"
                        MediaPlayerBackend.State.PLAYING -> "PLAYING"
                        MediaPlayerBackend.State.PAUSED -> "PAUSED_PLAYBACK"
                        MediaPlayerBackend.State.TRANSITIONING -> "TRANSITIONING"
                        MediaPlayerBackend.State.ERROR -> "STOPPED"
                    }
                    mediaSession.updateDmr(
                        state = transportState,
                        title = info.device.currentDmrTitle(),
                        positionMs = snapshot.positionMs,
                        durationMs = snapshot.durationMs,
                        playing = snapshot.state == MediaPlayerBackend.State.PLAYING,
                        active = dmrRunning,
                    )
                }
            }
            refreshNetwork()
        } catch (e: Exception) {
            dmrError = e.message ?: e.javaClass.simpleName
            dmrRunning = false
            dmrDevice = null
        } finally {
            dmrBusy = false
        }
    }

    suspend fun stopDmr() {
        if (!dmrRunning && dmrDevice == null) {
            return
        }
        mediaSession.clear()
        dmrBusy = true
        val device = dmrDevice
        dmrDevice = null
        device?.setAvStateListener(null)
        withContext(Dispatchers.IO) { device?.stop() }
        dmrRunning = false
        dmrStatus = ""
        dmrBusy = false
        updateRadioLocks()
    }

    suspend fun discoverRenderers() {
        if (dmcDiscovering) {
            return
        }
        dmcDiscovering = true
        dmcError = null
        try {
            val found = withContext(Dispatchers.IO) { dmcClient.findRenderers(5_000) }
            renderers = found
            val current = selectedRenderer
            if (current == null || found.none { it.location == current.location }) {
                selectedRenderer = found.firstOrNull()
            }
            if (found.isEmpty()) {
                dmcError = "未发现 MediaRenderer（检查同一局域网与组播）"
            }
        } catch (e: Exception) {
            dmcError = e.message ?: e.javaClass.simpleName
        } finally {
            dmcDiscovering = false
        }
    }

    fun selectRenderer(renderer: DmcClient.Renderer) {
        selectedRenderer = renderer
        dmcError = null
    }

    suspend fun castTrack(file: File, title: String, force: Boolean = false) {
        castTrack(LocalFileResource(file), title, force)
    }

    suspend fun castTrack(resource: MediaResource, title: String, force: Boolean = false) {
        if (!dmcEnabled) {
            dmcError = "投送端已关闭，请在发送者面板打开 DMC"
            return
        }
        val renderer = selectedRenderer ?: run {
            dmcError = "请先选择投送设备"
            return
        }
        val dms = dmsDevice ?: run {
            dmcError = "请先启动曲库共享（投送媒体端点由 DMS 提供）"
            return
        }
        if (!force) {
            val mime = MediaProtocolInfo.forFileName(resource.name)?.mime ?: resource.mime
            val sink = withContext(Dispatchers.IO) {
                runCatching { dmcClient.getSinkProtocolInfo(renderer) }.getOrNull()
            }
            val sinkText = sink?.takeIf { it.successful }
                ?.let { DmcClient.extractTag(it.body, "Sink") }.orEmpty()
            if (mime != null && sinkText.isNotEmpty() &&
                !dmcClient.sinkSupportsMime(sinkText, mime)
            ) {
                pendingCast = PendingCast(resource, title)
                val extension = resource.name.substringAfterLast('.', resource.name).uppercase()
                dmcFormatWarning =
                    "该设备未声明支持 $extension（$mime），可能无法播放。"
                return
            }
        }
        doCast(renderer, dms, resource, title)
    }

    /**
     * Casts one WebDAV entry to the selected renderer. The controller owns the
     * NAS client for the lifetime of the cast, so closing the NAS screen does
     * not close the stream under the renderer.
     *
     * Returns null on success or a user-facing error message.
     */
    suspend fun castCatalogTrack(
        url: HttpUrl,
        name: String,
        size: Long,
        title: String,
        albumId: String? = null,
    ): String? {
        if (size <= 0L) {
            return "服务器未提供文件大小，无法投送"
        }
        val saved = withContext(Dispatchers.IO) {
            WebDavCredentialStore(appContext).load()
        } ?: return "NAS 凭据不存在，请先在 NAS 页面连接"
        if (!url.host.equals(saved.account.baseUrl.host, ignoreCase = true) ||
            url.port != saved.account.baseUrl.port ||
            url.scheme != saved.account.baseUrl.scheme
        ) {
            return "NAS 地址与保存的凭据不一致，请重新连接"
        }
        if (casting) {
            stopCast()
        }
        closeCastClient()
        val client = WebDavClient(saved.account, saved.password, saved.authType)
        castClient = client
        val entry = WebDavEntry(
            url = url,
            path = url.encodedPath,
            name = name,
            isCollection = false,
            size = size,
        )
        castAlbumId = albumId
        castTrack(WebDavResource(client, entry), title)
        return if (casting || dmcFormatWarning != null) {
            null
        } else {
            val message = dmcError ?: "投送失败"
            closeCastClient()
            message
        }
    }

    suspend fun castNasEntry(entry: WebDavEntry): String? {
        if (!entry.isAudio) {
            return "只能投送音频文件"
        }
        if (!entry.playbackSupported) {
            return "该格式暂不支持播放"
        }
        if (entry.size == null) {
            return "服务器未提供文件大小，无法投送"
        }
        val saved = withContext(Dispatchers.IO) {
            WebDavCredentialStore(appContext).load()
        } ?: return "NAS 凭据不存在，请先在 NAS 页面连接"
        if (!entry.url.host.equals(saved.account.baseUrl.host, ignoreCase = true) ||
            entry.url.port != saved.account.baseUrl.port ||
            entry.url.scheme != saved.account.baseUrl.scheme
        ) {
            return "NAS 地址与保存的凭据不一致，请重新连接"
        }
        if (casting) {
            stopCast()
        }
        closeCastClient()
        val client = WebDavClient(saved.account, saved.password, saved.authType)
        castClient = client
        val resource = WebDavResource(client, entry)
        castTrack(resource, entry.name.substringBeforeLast('.', entry.name))
        return if (casting || dmcFormatWarning != null) {
            null
        } else {
            val message = dmcError ?: "投送失败"
            closeCastClient()
            message
        }
    }

    suspend fun confirmCastAnyway() {
        val pending = pendingCast ?: return
        pendingCast = null
        dmcFormatWarning = null
        val renderer = selectedRenderer ?: return
        val dms = dmsDevice ?: run {
            dmcError = "曲库共享已停止"
            return
        }
        doCast(renderer, dms, pending.resource, pending.title)
    }

    fun dismissFormatWarning() {
        pendingCast = null
        dmcFormatWarning = null
        closeCastClient()
    }

    private fun closeCastClient() {
        castClient?.close()
        castClient = null
    }

    private suspend fun doCast(
        renderer: DmcClient.Renderer,
        dms: DmsDevice,
        resource: MediaResource,
        title: String,
    ) {
        val url = dms.mediaUrl(resource) ?: run {
            dmcError = "无法生成媒体 URL"
            return
        }
        if (casting) {
            stopCast()
        }
        dmcError = null
        try {
            NativeBridge.stopPlayback()
            context.stopService(Intent(context, PlaybackService::class.java))
            val mime = MediaProtocolInfo.forFileName(resource.name)?.mime ?: resource.mime
            val metadata = mime?.let {
                DmcClient.buildDidlMetadata(
                    title = title,
                    mediaUrl = url,
                    mime = it,
                    sizeBytes = resource.length,
                )
            }
            val set = withContext(Dispatchers.IO) {
                dmcClient.setAvTransportUri(renderer, url, metadata)
            }
            if (!set.successful) {
                val error = DmcClient.extractTag(set.body, "errorDescription").orEmpty()
                throw IllegalStateException("SetAVTransportURI 失败 HTTP ${set.code} $error")
            }
            val play = withContext(Dispatchers.IO) { dmcClient.play(renderer) }
            if (!play.successful) {
                throw IllegalStateException("Play 失败 HTTP ${play.code}")
            }
            casting = true
            castPaused = false
            castTitle = title
            castState = "TRANSITIONING"
            castPositionMs = 0L
            castDurationMs = 0L
            updateRadioLocks()
            startCastPolling(renderer)
        } catch (e: Exception) {
            casting = false
            dmcError = e.message ?: e.javaClass.simpleName
        }
    }

    suspend fun pauseOrResumeCast() {
        if (!casting) {
            return
        }
        val renderer = selectedRenderer ?: return
        val response = withContext(Dispatchers.IO) {
            if (castPaused) dmcClient.play(renderer) else dmcClient.pause(renderer)
        }
        if (response.successful) {
            castPaused = !castPaused
        } else {
            dmcError = "播放控制失败 HTTP ${response.code}"
        }
    }

    suspend fun stopCast() {
        castPollJob?.cancel()
        castPollJob = null
        val wasCasting = casting
        casting = false
        castPaused = false
        castState = ""
        castPositionMs = 0L
        castDurationMs = 0L
        castAlbumId = null
        castOffline = false
        mediaSession.clear()
        updateRadioLocks()
        val renderer = selectedRenderer
        if (wasCasting && renderer != null) {
            withContext(Dispatchers.IO) {
                runCatching { dmcClient.stop(renderer) }
            }
        }
        closeCastClient()
    }

    suspend fun seekCast(positionMs: Long) {
        if (!casting) {
            return
        }
        val renderer = selectedRenderer ?: return
        val response = withContext(Dispatchers.IO) { dmcClient.seek(renderer, positionMs) }
        if (response.successful) {
            castPositionMs = positionMs
        } else {
            dmcError = "Seek 失败 HTTP ${response.code}"
        }
    }

    suspend fun setCastVolume(volume: Int) {
        castVolume = volume.coerceIn(0, 100)
        val renderer = selectedRenderer ?: return
        withContext(Dispatchers.IO) { dmcClient.setVolume(renderer, castVolume) }
    }

    private fun startCastPolling(renderer: DmcClient.Renderer) {
        castPollJob?.cancel()
        castPollJob = dmcScope.launch {
            var consecutiveFailures = 0
            while (isActive && casting) {
                val info = withContext(Dispatchers.IO) {
                    runCatching { dmcClient.getTransportInfo(renderer) }.getOrNull()
                }
                val position = withContext(Dispatchers.IO) {
                    runCatching { dmcClient.getPositionInfo(renderer) }.getOrNull()
                }
                val volume = withContext(Dispatchers.IO) {
                    runCatching { dmcClient.getVolume(renderer) }.getOrNull()
                }
                val reachable = info?.successful == true || position?.successful == true
                if (reachable) {
                    consecutiveFailures = 0
                    if (castOffline) {
                        castOffline = false
                        dmcError = null
                        Log.i(TAG, "DMC 渲染器已恢复")
                    }
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= OFFLINE_FAILURE_THRESHOLD && !castOffline) {
                        castOffline = true
                        dmcError = "渲染器已离线（投送控制轮询连续失败）；继续尝试重连，可停止投送并回退本地播放"
                        Log.w(TAG, "DMC 渲染器离线：连续 $consecutiveFailures 次轮询失败")
                    }
                }
                if (info?.successful == true) {
                    DmcClient.extractTag(info.body, "CurrentTransportState")?.let { state ->
                        castState = state
                        when (state) {
                            "PAUSED_PLAYBACK" -> castPaused = true
                            "PLAYING" -> castPaused = false
                        }
                    }
                }
                if (position?.successful == true) {
                    DmcClient.timeToMillis(DmcClient.extractTag(position.body, "RelTime"))
                        ?.let { castPositionMs = it }
                    DmcClient.timeToMillis(DmcClient.extractTag(position.body, "TrackDuration"))
                        ?.let { castDurationMs = it }
                }
                if (volume?.successful == true) {
                    DmcClient.extractTag(volume.body, "CurrentVolume")
                        ?.toIntOrNull()
                        ?.let { castVolume = it.coerceIn(0, 100) }
                }
                if (role == DlnaRole.SENDER) {
                    mediaSession.updateDmc(
                        state = castState,
                        target = renderer.friendlyName,
                        title = castTitle,
                        positionMs = castPositionMs,
                        durationMs = castDurationMs,
                        paused = castPaused,
                        active = casting,
                    )
                }
                delay(1_000)
            }
        }
    }

    suspend fun stopAll() {
        stopCast()
        stopDms()
        stopDmr()
    }

    fun release() {
        castPollJob?.cancel()
        castPollJob = null
        closeCastClient()
        val castingRenderer = if (casting) selectedRenderer else null
        if (castingRenderer != null) {
            thread(name = "dmc-release", isDaemon = true) {
                runCatching { dmcClient.stop(castingRenderer) }
            }
        }
        casting = false
        mediaSession.release()
        try {
            if (radioLocksHeld) {
                multicastLock?.release()
                wifiLock?.release()
                radioLocksHeld = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放 DLNA 无线锁失败: ${e.message}")
        }
        dmcScope.cancel()
        val dms = dmsDevice
        val dmr = dmrDevice
        dmsDevice = null
        dmrDevice = null
        if (dms != null) {
            DmsService.hide(appContext)
        }
        if (dms != null || dmr != null) {
            thread(name = "dlna-release", isDaemon = true) {
                dms?.shutdown()
                dmr?.stop()
            }
        }
    }

    private data class DmsStartInfo(
        val device: DmsDevice,
        val descriptionUrl: String,
        val albums: Int,
        val tracks: Int,
        val localLibrary: Boolean,
    )

    private companion object {
        const val TAG = "M0Dlna"
        const val OFFLINE_FAILURE_THRESHOLD = 3
    }

    private data class DmrStartInfo(
        val device: DmrDevice,
        val descriptionUrl: String,
    )
}
