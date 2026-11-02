// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gu.laoshitou.dlna.DlnaPermissions
import com.gu.laoshitou.dlna.DmcClient
import com.gu.laoshitou.dlna.DmrDspConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun DlnaScreen(
    controller: DlnaController,
    playerState: PlayerState,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var allFiles by remember { mutableStateOf(controller.hasAllFilesAccess()) }
    var nearbyMissing by remember { mutableStateOf(DlnaPermissions.missing(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFiles = controller.hasAllFilesAccess()
                nearbyMissing = DlnaPermissions.missing(context)
                controller.refreshNetwork()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { controller.refreshNetwork() }

    val nearbyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        nearbyMissing = DlnaPermissions.missing(context)
    }

    fun openAllFilesSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        }
        runCatching { context.startActivity(intent) }.onFailure {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
    }

    MaterialTheme(colorScheme = HifiColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text("← 返回") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "DLNA 局域网",
                        style = MaterialTheme.typography.titleMedium,
                        color = HifiCyan,
                    )
                }
                Text(
                    "仅在本机 Wi-Fi/以太网内通信，不连接互联网。每次接入新的局域网都应重新选择角色；" +
                        "同一连接只启用一个角色，发送者不会启动接收服务，接收者不会启动曲库共享。",
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiMuted,
                )

                HifiCard("当前网络") {
                    Text(
                        controller.networkAddress?.let { "局域网 IPv4：$it" }
                            ?: "未连接 Wi-Fi/以太网私有局域网，DLNA 不可用",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { controller.refreshNetwork() }) {
                        Text("刷新")
                    }
                }

                Text("本机角色", style = MaterialTheme.typography.titleMedium, color = HifiAmber)
                for (role in DlnaRole.entries) {
                    RoleCard(
                        role = role,
                        selected = controller.role == role,
                        onClick = { scope.launch { controller.selectRole(role) } },
                    )
                }

                when (controller.role) {
                    DlnaRole.SENDER -> SenderPanel(
                        controller = controller,
                        playerState = playerState,
                        allFiles = allFiles,
                        nearbyMissing = nearbyMissing,
                        onOpenAllFilesSettings = ::openAllFilesSettings,
                        onRequestNearby = { nearbyLauncher.launch(DlnaPermissions.runtimePermissions().toTypedArray()) },
                        scope = scope,
                    )
                    DlnaRole.RECEIVER -> ReceiverPanel(
                        controller = controller,
                        scope = scope,
                        onStartDmr = {
                            scope.launch { controller.startDmr(buildDmrDspConfig(playerState)) }
                        },
                    )
                    DlnaRole.OFFLINE -> HifiCard("保持离线") {
                        Text(
                            "已关闭 DLNA。不会加入组播、不会监听端口、不会发起网络请求。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HifiMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SenderPanel(
    controller: DlnaController,
    playerState: PlayerState,
    allFiles: Boolean,
    nearbyMissing: List<String>,
    onOpenAllFilesSettings: () -> Unit,
    onRequestNearby: () -> Unit,
    scope: CoroutineScope,
) {
    HifiCard("发送者子开关") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "曲库共享（DMS）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = controller.dmsEnabled,
                onCheckedChange = { scope.launch { controller.setDmsEnabled(it) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "投送端（DMC）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = controller.dmcEnabled,
                onCheckedChange = { scope.launch { controller.setDmcEnabled(it) } },
            )
        }
    }

    HifiCard("权限") {
        PermissionLine(
            label = "所有文件访问（读取 /sdcard/laoshitou 曲库）",
            granted = allFiles,
            action = if (!allFiles) {
                { OutlinedButton(onClick = onOpenAllFilesSettings) { Text("去授权") } }
            } else {
                null
            },
        )
        PermissionLine(
            label = "附近 Wi-Fi 权限（Android 13+）",
            granted = nearbyMissing.isEmpty(),
            action = if (nearbyMissing.isNotEmpty()) {
                { OutlinedButton(onClick = onRequestNearby) { Text("授权") } }
            } else {
                null
            },
        )
        if (!allFiles) {
            Text(
                "DMS 需要读取音乐库；授权后返回本页会自动刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = HifiAmber,
            )
        }
    }

    HifiCard("曲库共享（DMS）") {
        if (!controller.dmsEnabled) {
            Text(
                "曲库共享已关闭。投送端（DMC）依赖 DMS 的媒体端点，关闭后无法投送。",
                style = MaterialTheme.typography.bodyMedium,
                color = HifiMuted,
            )
            return@HifiCard
        }
        if (controller.dmsRunning) {
            Text(
                "共享中：${controller.dmsAlbums} 张专辑 / ${controller.dmsTracks} 首曲目",
                style = MaterialTheme.typography.bodyMedium,
                color = HifiGreen,
            )
            if (!controller.dmsSharesLocalLibrary) {
                Text(
                    if (allFiles) {
                        "本地曲库为空，当前仅提供 NAS 投送端点。"
                    } else {
                        "本地曲库未授权，当前仅提供 NAS 投送端点；授权后可加载本地曲库。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiAmber,
                )
            }
            Text(
                controller.dmsStatus,
                style = MaterialTheme.typography.bodySmall,
                color = HifiMuted,
            )
            Text(
                "同一局域网内的设备都可以浏览并播放你的曲库。URL 带随机 token 与正确文件后缀。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { controller.stopDms() } },
                    enabled = !controller.dmsBusy,
                ) {
                    Text(if (controller.dmsBusy) "停止中…" else "停止共享")
                }
                if (allFiles && !controller.dmsSharesLocalLibrary) {
                    OutlinedButton(
                        onClick = { scope.launch { controller.reloadDmsWithLocalLibrary() } },
                        enabled = !controller.dmsBusy,
                    ) {
                        Text("加载本地曲库")
                    }
                }
            }
        } else {
            Button(
                onClick = { scope.launch { controller.startDms() } },
                enabled = !controller.dmsBusy && controller.networkAddress != null,
            ) {
                Text(if (controller.dmsBusy) "启动中…" else "启动曲库共享")
            }
            if (!allFiles) {
                Text(
                    "未授权本地曲库：仍可启动共享用于 NAS 投送；授权后才会共享本地曲库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiAmber,
                )
            }
        }
        controller.dmsError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (controller.dmsEnabled) {
        val miui = remember { isMiuiLike() }
        HifiCard("息屏保活（DMS 长驻）") {
            Text(
                if (miui) {
                    "DMS 需要在本机息屏后继续提供服务。小米/HyperOS 的省电策略默认会冻结后台应用并切断局域网连接，" +
                        "导致电视/音箱无法浏览或拉流（Xiaomi 17 真机已验证）。"
                } else {
                    "DMS 需要在本机息屏后继续提供服务。部分系统的电池优化会限制后台联网，" +
                        "可能导致电视/音箱无法浏览或拉流。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (miui) {
                    "请手动设置：设置 → 应用信息 → 老石头 → 电量详情 → 省电策略 → 无限制。" +
                        "最近任务锁定只能防清理，不能替代此设置。"
                } else {
                    "请手动在系统应用电池设置中允许本应用后台运行或关闭电池优化。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = HifiAmber,
            )
            Text(
                "本应用只做文字提示，不会自动修改系统设置或跳转页面；" +
                    "完成上述设置后返回本页即可，无需重启。",
                style = MaterialTheme.typography.bodySmall,
                color = HifiMuted,
            )
        }
    }

    DmcPanel(controller, playerState, scope)
}

@Composable
private fun DmcPanel(
    controller: DlnaController,
    playerState: PlayerState,
    scope: CoroutineScope,
) {
    var pickedAlbum by remember { mutableStateOf<AlbumItem?>(null) }
    var pickedTrack by remember { mutableStateOf<TrackItem?>(null) }

    HifiCard("投送端（DMC）") {
        if (!controller.dmcEnabled) {
            Text(
                "投送端已关闭。",
                style = MaterialTheme.typography.bodyMedium,
                color = HifiMuted,
            )
            return@HifiCard
        }
        Text(
            "发现局域网 MediaRenderer，并把本地曲目投送过去。投送会停止本地播放；" +
                "媒体文件由 DMS 的 token 端点提供，因此需要先启动曲库共享。",
            style = MaterialTheme.typography.bodySmall,
            color = HifiMuted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { scope.launch { controller.discoverRenderers() } },
                enabled = !controller.dmcDiscovering,
            ) {
                Text(if (controller.dmcDiscovering) "发现中…" else "发现投送设备")
            }
            Spacer(Modifier.width(8.dp))
            if (!controller.dmsRunning) {
                Text("请先启动曲库共享", style = MaterialTheme.typography.bodySmall, color = HifiAmber)
            }
        }

        if (controller.renderers.isEmpty()) {
            Text("尚未发现 MediaRenderer。", style = MaterialTheme.typography.bodySmall, color = HifiMuted)
        } else {
            for (renderer in controller.renderers) {
                val selected = controller.selectedRenderer?.location == renderer.location
                TextButton(
                    onClick = { controller.selectRenderer(renderer) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        (if (selected) "✓ " else "") + renderer.friendlyName +
                            " · " + (renderer.location.host ?: "?"),
                        color = if (selected) HifiCyan else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        controller.selectedRenderer?.let { renderer ->
            Text(
                "目标：${renderer.friendlyName}",
                style = MaterialTheme.typography.bodySmall,
                color = HifiGreen,
            )
            if (DmcClient.isLocalRenderer(renderer, controller.networkAddress)) {
                Text(
                    "提示：该渲染器运行在本机（同机第三方应用）。MIUI/HyperOS 可能" +
                        "冻结它的后台 UPnP 服务，导致投送中突然离线；请到 设置 → " +
                        "应用信息 → 该渲染器应用（如 Kodi）→ 电量详情 → " +
                        "省电策略 → 无限制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiAmber,
                )
            }
            if (playerState.albums.isEmpty()) {
                Text(
                    "曲库为空，请先在主界面扫描 laoshitou/ 曲库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiAmber,
                )
            } else if (pickedAlbum == null) {
                Text("选择要投送的专辑：", style = MaterialTheme.typography.bodySmall)
                for (album in playerState.albums) {
                    TextButton(onClick = { pickedAlbum = album; pickedTrack = null }) {
                        Text(album.name)
                    }
                }
            } else {
                val album = pickedAlbum ?: return@let
                Text("专辑：${album.name}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pickedAlbum = null; pickedTrack = null }) {
                    Text("← 返回专辑列表")
                }
                for (track in album.tracks) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            track.name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { pickedTrack = track }) {
                            Text(if (pickedTrack == track) "已选" else "选择")
                        }
                    }
                }
            }
            val track = pickedTrack
            Button(
                onClick = {
                    if (track != null) {
                        scope.launch { controller.castTrack(File(track.path), track.name) }
                    }
                },
                enabled = track != null && controller.dmsRunning && !controller.casting,
            ) {
                Text(
                    if (controller.casting) "投送中…" else
                        "投送到 ${renderer.friendlyName}",
                )
            }
        }
        controller.dmcError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (controller.casting) {
        HifiCard("投送控制") {
            Text(controller.castTitle, style = MaterialTheme.typography.bodyMedium)
            Text(
                "状态：${controller.castState}  " +
                    "${DmcClient.formatTime(controller.castPositionMs)} / " +
                    "${DmcClient.formatTime(controller.castDurationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = HifiMuted,
            )
            if (controller.castOffline) {
                Text(
                    "渲染器已离线，正在重试…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val duration = controller.castDurationMs.coerceAtLeast(1L)
            var seek by remember(controller.castPositionMs) {
                mutableStateOf(controller.castPositionMs.toFloat())
            }
            Slider(
                value = seek.coerceIn(0f, duration.toFloat()),
                onValueChange = { seek = it },
                valueRange = 0f..duration.toFloat(),
                onValueChangeFinished = {
                    scope.launch { controller.seekCast(seek.toLong()) }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { scope.launch { controller.pauseOrResumeCast() } },
                ) {
                    Text(if (controller.castPaused) "继续" else "暂停")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { scope.launch { controller.stopCast() } }) {
                    Text("停止投送")
                }
            }
            Text("音量 ${controller.castVolume}", style = MaterialTheme.typography.bodySmall)
            var volume by remember(controller.castVolume) {
                mutableStateOf(controller.castVolume.toFloat())
            }
            Slider(
                value = volume,
                onValueChange = { volume = it },
                valueRange = 0f..100f,
                onValueChangeFinished = {
                    scope.launch { controller.setCastVolume(volume.toInt()) }
                },
            )
        }
    }

    controller.dmcFormatWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { controller.dismissFormatWarning() },
            title = { Text("格式可能不受支持") },
            text = {
                Text("$warning 仍要尝试投送吗？", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Button(onClick = { scope.launch { controller.confirmCastAnyway() } }) {
                    Text("仍要尝试")
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.dismissFormatWarning() }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ReceiverPanel(
    controller: DlnaController,
    scope: kotlinx.coroutines.CoroutineScope,
    onStartDmr: () -> Unit,
) {
    HifiCard("接收投送（DMR）") {
        Text(
            "手机作为 DLNA 音箱接收局域网投送；使用原生 FFmpeg + 老石头 DSP 管线" +
                "（EQ/Crossfeed/BRIR 生效），不支持的格式自动回退系统播放器。" +
                "接收期间会停止本地播放。",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (controller.dmrRunning) {
            Text("接收中：${controller.dmrStatus}", style = MaterialTheme.typography.bodySmall, color = HifiGreen)
            OutlinedButton(
                onClick = { scope.launch { controller.stopDmr() } },
                enabled = !controller.dmrBusy,
            ) {
                Text(if (controller.dmrBusy) "停止中…" else "停止接收")
            }
        } else {
            Button(
                onClick = onStartDmr,
                enabled = !controller.dmrBusy && controller.networkAddress != null,
            ) {
                Text(if (controller.dmrBusy) "启动中…" else "启动接收")
            }
        }
        controller.dmrError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RoleCard(role: DlnaRole, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (selected) BorderStroke(1.dp, HifiAmber) else null,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    role.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) HifiAmber else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text("已选择", style = MaterialTheme.typography.labelSmall, color = HifiCyan)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                role.description,
                style = MaterialTheme.typography.bodySmall,
                color = HifiMuted,
            )
        }
    }
}

@Composable
private fun PermissionLine(
    label: String,
    granted: Boolean,
    action: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            if (granted) "已授权" else "未授权",
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) HifiGreen else HifiAmber,
        )
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

/** Captures the player's current DSP settings for a DMR session. */
private fun buildDmrDspConfig(state: PlayerState): DmrDspConfig = DmrDspConfig(
    processing = state.processing,
    eqPath = state.eqPath.takeIf { it.isNotEmpty() && File(it).exists() } ?: "",
    crossfeed = state.crossfeed,
    eqCascade = state.eqCascade,
    eqUseSlope = state.eqSlopeEnabled,
    eqSlope = state.eqSlope,
    forcedOutputRate = state.outputRateOverride,
    resamplerQuality = state.resamplerQuality,
    crossfeedWidth = state.crossfeedWidth,
    stereoWidth = state.stereoWidth,
    loudnessEnabled = state.loudness,
    limiterEnabled = state.limiterEnabled,
    limiterThresholdDb = state.limiterThresholdDb,
    autoUsb192k = state.usbAuto192k,
    brirPath = state.brirPath,
    brirEnabled = state.brirEnabled,
    brirMix = state.brirMix,
    eqLinearPhase = state.eqLinearPhase,
    multibandEnabled = state.multibandEnabled,
    multibandLowHz = state.multibandLowHz,
    multibandHighHz = state.multibandHighHz,
    multibandThresholdDb = state.multibandThresholdDb,
)

/** Xiaomi/Redmi/Poco all run HyperOS/MIUI, whose PowerKeeper can freeze background apps. */
private fun isMiuiLike(): Boolean {
    val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
    return maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")
}

@Composable
private fun HifiCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = HifiCyan)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
