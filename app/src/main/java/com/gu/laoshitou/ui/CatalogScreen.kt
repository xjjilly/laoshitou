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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.gu.laoshitou.catalog.CatalogAlbum
import com.gu.laoshitou.catalog.CatalogAlbumDetail
import com.gu.laoshitou.catalog.CatalogClient
import com.gu.laoshitou.catalog.CatalogSettingsStore
import com.gu.laoshitou.catalog.CatalogTrack
import com.gu.laoshitou.catalog.PlayedTrackStore
import kotlinx.coroutines.launch

/**
 * SQL 曲库：NAS 曲库服务的专辑/曲目浏览与本地播放。
 *
 * 播放过与未播放的曲目用颜色区分：已播放为灰色，未播放为正常前景色。
 */
@Composable
fun CatalogScreen(
    onClose: () -> Unit,
    onPlayTrack: suspend (CatalogTrack) -> String?,
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

    LaunchedEffect(Unit) {
        playedStore.load().forEach { played[it] = true }
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

    fun play(track: CatalogTrack) {
        scope.launch {
            error = null
            status = "正在打开：${track.title}"
            val failure = onPlayTrack(track)
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
                    CatalogTrackList(
                        detail = detail!!,
                        played = played,
                        onPlay = ::play,
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
    onPlay: (CatalogTrack) -> Unit,
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
        TextButton(onClick = { onPlay(detail.tracks.firstOrNull() ?: return@TextButton) }) {
            Text("播放全部")
        }
    }
    LazyColumn(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(detail.tracks, key = { it.id }) { track ->
            val isPlayed = played[track.id] == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(track) }
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
