// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gu.laoshitou.NativeBridge
import com.gu.laoshitou.dlna.DmcClient
import com.gu.laoshitou.catalog.CatalogAlbum
import com.gu.laoshitou.catalog.CatalogAlbumDetail
import com.gu.laoshitou.catalog.CatalogClient
import com.gu.laoshitou.catalog.CatalogSettingsStore
import com.gu.laoshitou.catalog.CatalogTrack
import com.gu.laoshitou.catalog.PlayedTrackStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SQL 曲库：NAS 曲库服务的专辑/曲目浏览与本地播放。
 *
 * 播放过与未播放的曲目用颜色区分：已播放为灰色，未播放为正常前景色。
 */
@Composable
fun CatalogScreen(
    onClose: () -> Unit,
    controller: DlnaController,
    onOpenDlna: () -> Unit,
    onPlayTrack: suspend (CatalogTrack, List<CatalogTrack>, Int) -> String?,
    onCastTrack: suspend (CatalogTrack) -> String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { CatalogSettingsStore(context) }
    val playedStore = remember { PlayedTrackStore(context) }
    val initial = remember { settingsStore.load() }

    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var showToken by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf<CatalogClient?>(null) }
    var albums by remember { mutableStateOf<List<CatalogAlbum>>(emptyList()) }
    var detail by remember { mutableStateOf<CatalogAlbumDetail?>(null) }
    val covers = remember { mutableStateMapOf<String, ImageBitmap>() }
    val played = remember { mutableStateMapOf<String, Boolean>() }
    var loading by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            playedStore.load().forEach { played[it] = true }
            if (!dragging) {
                playing = NativeBridge.isPlaying()
                durationMs = NativeBridge.playbackDurationMs()
                positionMs = NativeBridge.playbackPositionMs()
            }
            delay(500)
        }
    }

    fun connect() {
        val url = baseUrl.trim().trimEnd('/')
        val apiToken = token.trim()
        if (url.isEmpty()) {
            error = "请输入 API 地址"
            return
        }
        loading = true
        error = null
        status = "连接中…"
        scope.launch {
            val next = CatalogClient(url, apiToken)
            try {
                val list = next.listAlbums()
                client?.close()
                client = next
                albums = list
                connected = true
                status = "已连接：${list.size} 张专辑"
                settingsStore.save(url, apiToken)
            } catch (e: Exception) {
                next.close()
                error = e.message ?: "连接失败"
                status = ""
            } finally {
                loading = false
            }
        }
    }

    fun openAlbum(album: CatalogAlbum) {
        val active = client ?: return
        loading = true
        error = null
        scope.launch {
            try {
                detail = active.getAlbum(album.id)
            } catch (e: Exception) {
                error = e.message ?: "读取专辑失败"
            } finally {
                loading = false
            }
        }
    }

    fun play(track: CatalogTrack, queue: List<CatalogTrack>, index: Int) {
        scope.launch {
            error = null
            status = "正在打开：${track.title}"
            val failure = onPlayTrack(track, queue, index)
            if (failure == null) {
                playedStore.markPlayed(track.id)
                played[track.id] = true
                status = "正在播放：${track.title}"
            } else {
                error = failure
                status = "播放失败"
            }
        }
    }

    fun cast(track: CatalogTrack) {
        scope.launch {
            error = null
            status = "正在投送：${track.title}"
            val failure = onCastTrack(track)
            if (failure == null) {
                status = "已投送：${track.title}"
            } else {
                error = failure
                status = "投送失败"
            }
        }
    }

    BackHandler {
        if (detail != null) {
            detail = null
        } else {
            onClose()
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
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        if (detail != null) {
                            detail = null
                        } else {
                            onClose()
                        }
                    }) {
                        Text("← 返回")
                    }
                    Text(
                        text = "SQL 曲库",
                        style = MaterialTheme.typography.titleLarge,
                        color = HifiAmber,
                        modifier = Modifier.weight(1f),
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = HifiCyan,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (controller.casting) {
                            "投送中：${controller.castTitle}"
                        } else {
                            "投送目标：" +
                                (controller.selectedRenderer?.friendlyName ?: "未选择")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (controller.casting) HifiGreen else HifiMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!controller.casting) {
                        TextButton(onClick = onOpenDlna) {
                            Text("DLNA 设置", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (controller.casting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${DmcClient.formatTime(controller.castPositionMs)} / " +
                                    DmcClient.formatTime(controller.castDurationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = HifiMuted,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch { controller.pauseOrResumeCast() }
                            }) {
                                Text(
                                    text = if (controller.castPaused) "继续" else "暂停",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch { controller.stopCast() }
                            }) {
                                Text("停止投送", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val castDuration = controller.castDurationMs.coerceAtLeast(1L)
                        var castSeek by remember(controller.castPositionMs) {
                            mutableStateOf(controller.castPositionMs.toFloat())
                        }
                        Slider(
                            value = castSeek.coerceIn(0f, castDuration.toFloat()),
                            onValueChange = { castSeek = it },
                            valueRange = 0f..castDuration.toFloat(),
                            onValueChangeFinished = {
                                scope.launch { controller.seekCast(castSeek.toLong()) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "音量 ${controller.castVolume}",
                                style = MaterialTheme.typography.labelSmall,
                                color = HifiMuted,
                            )
                            var castVolume by remember(controller.castVolume) {
                                mutableStateOf(controller.castVolume.toFloat())
                            }
                            Slider(
                                value = castVolume,
                                onValueChange = { castVolume = it },
                                valueRange = 0f..100f,
                                onValueChangeFinished = {
                                    scope.launch { controller.setCastVolume(castVolume.toInt()) }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                            )
                        }
                    }
                }

                if (playing && durationMs > 0L) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatDuration(positionMs.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = HifiMuted,
                        )
                        Slider(
                            value = if (dragging) {
                                dragValue.coerceIn(0f, durationMs.toFloat())
                            } else {
                                positionMs.toFloat().coerceIn(0f, durationMs.toFloat())
                            },
                            onValueChange = {
                                dragging = true
                                dragValue = it
                            },
                            onValueChangeFinished = {
                                NativeBridge.seekPlayback(dragValue.toLong())
                                positionMs = dragValue.toLong()
                                dragging = false
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            text = formatDuration(durationMs.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = HifiMuted,
                        )
                    }
                }

                if (!connected) {
                    CatalogConnectionCard(
                        baseUrl = baseUrl,
                        token = token,
                        showToken = showToken,
                        loading = loading,
                        error = error,
                        status = status,
                        onBaseUrlChange = { baseUrl = it },
                        onTokenChange = { token = it },
                        onToggleToken = { showToken = !showToken },
                        onConnect = ::connect,
                    )
                } else if (detail == null) {
                    CatalogAlbumList(
                        albums = albums,
                        covers = covers,
                        client = client,
                        onOpen = ::openAlbum,
                    )
                } else {
                    val currentDetail = detail!!
                    CatalogTrackList(
                        detail = currentDetail,
                        played = played,
                        onPlay = { track, index -> play(track, currentDetail.tracks, index) },
                        onCast = ::cast,
                    )
                }

                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiGreen,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogConnectionCard(
    baseUrl: String,
    token: String,
    showToken: Boolean,
    loading: Boolean,
    error: String?,
    status: String,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onToggleToken: () -> Unit,
    onConnect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("连接曲库服务", style = MaterialTheme.typography.labelSmall, color = HifiCyan)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("API 地址") },
                placeholder = { Text("http://192.168.1.10:9007") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text("API Token") },
                singleLine = true,
                visualTransformation = if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = onToggleToken) {
                        Text(if (showToken) "隐藏" else "显示")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onConnect, enabled = !loading) {
                Text(if (loading) "连接中…" else "连接")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "曲库 API 与 WebDAV 使用不同凭据；播放时通过已保存的 WebDAV 连接取流。",
                style = MaterialTheme.typography.bodySmall,
                color = HifiMuted,
            )
        }
    }
}

@Composable
private fun CatalogAlbumList(
    albums: List<CatalogAlbum>,
    covers: MutableMap<String, ImageBitmap>,
    client: CatalogClient?,
    onOpen: (CatalogAlbum) -> Unit,
) {
    Text(
        text = "专辑 · ${albums.size} 张",
        style = MaterialTheme.typography.labelSmall,
        color = HifiMuted,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(albums, key = { it.id }) { album ->
            LaunchedEffect(album.id, client) {
                if (client != null && covers[album.id] == null) {
                    val bytes = client.loadCover(album)
                    if (bytes != null) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            covers[album.id] = bitmap.asImageBitmap()
                        }
                    }
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(album) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumCover(cover = covers[album.id], title = album.title)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(
                                album.artist.takeIf { it.isNotBlank() },
                                album.year.takeIf { it > 0 }?.toString(),
                                "${album.trackCount} 首",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = HifiMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCover(cover: ImageBitmap?, title: String) {
    val shape = RoundedCornerShape(8.dp)
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = HifiCyan,
            )
        }
    }
}

@Composable
private fun CatalogTrackList(
    detail: CatalogAlbumDetail,
    played: Map<String, Boolean>,
    onPlay: (CatalogTrack, Int) -> Unit,
    onCast: (CatalogTrack) -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = detail.album.title,
                style = MaterialTheme.typography.titleMedium,
                color = HifiAmber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    detail.album.artist.takeIf { it.isNotBlank() },
                    "${detail.tracks.size} 首",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = HifiMuted,
            )
        }
        TextButton(onClick = {
            detail.tracks.firstOrNull()?.let { onPlay(it, 0) }
        }) {
            Text("播放全部")
        }
    }
    LazyColumn(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(detail.tracks, key = { _, track -> track.id }) { index, track ->
            val isPlayed = played[track.id] == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(track, index) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPlayed) HifiMuted else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = trackSpec(track),
                    style = MaterialTheme.typography.labelSmall,
                    color = HifiMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = formatDuration(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = HifiMuted,
                )
                if (isPlayed) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "已播",
                        style = MaterialTheme.typography.labelSmall,
                        color = HifiGreen,
                    )
                }
                Text(
                    text = "投送",
                    style = MaterialTheme.typography.labelSmall,
                    color = HifiCyan,
                    modifier = Modifier
                        .clickable { onCast(track) }
                        .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

private fun trackSpec(track: CatalogTrack): String {
    val format = track.format.uppercase()
    return when {
        track.dsdRate > 0 -> "$format DSD${track.dsdRate}"
        track.sampleRate > 0 -> {
            val khz = track.sampleRate / 1000.0
            val rate = if (khz >= 100) {
                String.format("%.0f", khz)
            } else {
                String.format("%.1f", khz)
            }
            "$format $rate kHz" + if (track.bitDepth > 0) " / ${track.bitDepth} bit" else ""
        }
        else -> format
    }
}

private fun formatDuration(milliseconds: Int): String {
    if (milliseconds <= 0) {
        return "--:--"
    }
    val totalSeconds = milliseconds / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
