// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import com.gu.laoshitou.NativeBridge
import com.gu.laoshitou.catalog.CatalogAlbum
import com.gu.laoshitou.catalog.CatalogAlbumDetail
import com.gu.laoshitou.catalog.CatalogClient
import com.gu.laoshitou.catalog.CatalogSettingsStore
import com.gu.laoshitou.catalog.CatalogTrack
import com.gu.laoshitou.catalog.PlayedTrackStore
import com.gu.laoshitou.nas.NasCredentialExporter
import com.gu.laoshitou.nas.WebDavAccount
import com.gu.laoshitou.nas.WebDavAuthType
import com.gu.laoshitou.nas.WebDavCredentialStore
import com.gu.laoshitou.nas.WebDavUrl
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 深色主题 token（与 PC 端草案保持一致）
// ---------------------------------------------------------------------------

val AppBg = Color(0xFF1E1E1E)
val AppSurface = Color(0xFF252526)
val AppSurface2 = Color(0xFF2D2D30)
val AppBorder = Color(0xFF3C3C3C)
val AppText = Color(0xFFF2F2F2)
val AppMuted = Color(0xFFA6A6A6)
val AppDim = Color(0xFF6E6E6E)
val AppGold = Color(0xFFD4AF37)
val AppBlue = Color(0xFF4FC3F7)
val AppGreen = Color(0xFF66BB6A)

val AppDarkColors = darkColorScheme(
    primary = AppGold,
    onPrimary = Color(0xFF1A1408),
    secondary = AppBlue,
    onSecondary = Color(0xFF06131A),
    background = AppBg,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = AppSurface2,
    onSurfaceVariant = AppMuted,
    outline = AppBorder,
)

private val CoverBrushes = listOf(
    Brush.linearGradient(listOf(Color(0xFF2A2F66), Color(0xFF6B3FA0), Color(0xFF141927))),
    Brush.linearGradient(listOf(Color(0xFF274B3A), Color(0xFF56A07A), Color(0xFF102018))),
    Brush.linearGradient(listOf(Color(0xFF6B3A2A), Color(0xFFC07A45), Color(0xFF20130E))),
    Brush.linearGradient(listOf(Color(0xFF2B4A63), Color(0xFF4F9BC0), Color(0xFF0E1A22))),
    Brush.linearGradient(listOf(Color(0xFF5B2F4A), Color(0xFFA45D8B), Color(0xFF1C1018))),
    Brush.linearGradient(listOf(Color(0xFF3E3E24), Color(0xFF96913A), Color(0xFF17170D))),
)

private fun coverBrush(index: Int): Brush = CoverBrushes[index % CoverBrushes.size]

private data class PlaceholderAlbum(
    val name: String,
    val artist: String,
    val count: String,
)

private val NasPlaceholders = listOf(
    PlaceholderAlbum("BEYOND", "Unknown · DFF", "16 首"),
    PlaceholderAlbum("周华健", "Unknown · WAV", "44 首"),
    PlaceholderAlbum("自在极意", "JinJiBeWater_隼", "3 首"),
    PlaceholderAlbum("周杰伦", "2007 世界巡回演唱会", "19 首"),
    PlaceholderAlbum("THE PROTÉGÉ", "Gareth.T", "1 首"),
    PlaceholderAlbum("玻璃", "Gareth.T", "1 首"),
)

// ---------------------------------------------------------------------------
// 壳子
// ---------------------------------------------------------------------------

@Composable
fun AppShell(
    state: PlayerState,
    actions: PlayerActions,
    dlna: DlnaController,
    versionName: String,
    onPlayCatalogTrack: (CatalogTrack, List<CatalogTrack>, Int) -> Unit,
    onCastCatalogTrack: (CatalogTrack) -> Unit,
) {
    var page by remember { mutableStateOf(0) }
    var showAdvancedAudio by remember { mutableStateOf(false) }
    // Debug-only: jump to a page directly so UI can be captured without adb
    // input injection (MIUI blocks it on the test device).
    val debugContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val debuggable =
            (debugContext.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val activity = debugContext as? android.app.Activity
        if (debuggable) {
            if (activity?.intent?.getBooleanExtra("open_nas_settings", false) == true) {
                page = 1
            }
            if (activity?.intent?.getBooleanExtra("open_advanced_audio", false) == true) {
                showAdvancedAudio = true
            }
        }
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = NativeBridge.playbackPositionMs()
            durationMs = NativeBridge.playbackDurationMs()
            delay(500)
        }
    }

    MaterialTheme(colorScheme = AppDarkColors) {
        if (showAdvancedAudio) {
            AdvancedAudioPage(
                state = state,
                actions = actions,
                onBack = { showAdvancedAudio = false },
            )
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = Color(0xFF181818)) {
                        AppDrawer(
                            state = state,
                            actions = actions,
                            versionName = versionName,
                            onOpenAdvancedAudio = { showAdvancedAudio = true },
                            onClose = { scope.launch { drawerState.close() } },
                        )
                    }
                },
            ) {
                Scaffold(
                    containerColor = AppBg,
                    bottomBar = {
                        AppBottomBar(page = page, onSelect = { page = it })
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (page) {
                            0 -> HomeScreen(
                                state = state,
                                actions = actions,
                                dlna = dlna,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                onMenu = { scope.launch { drawerState.open() } },
                            )
                            1 -> LibraryScreen(
                                state = state,
                                dlna = dlna,
                                onMenu = { scope.launch { drawerState.open() } },
                                onPlayTrack = { album, index ->
                                    actions.openAlbum(album.relativePath)
                                    actions.playTrack(index)
                                    page = 0
                                },
                                onPlayCatalogTrack = { track, queue, index ->
                                    onPlayCatalogTrack(track, queue, index)
                                    page = 0
                                },
                                onCastCatalogTrack = onCastCatalogTrack,
                            )
                            else -> CastScreen(
                                dlna = dlna,
                                onMenu = { scope.launch { drawerState.open() } },
                            )
                        }
                        if (page != 0 && (state.playing || state.paused)) {
                            MiniPlayer(
                                state = state,
                                actions = actions,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onClick = { page = 0 },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedAudioPage(
    state: PlayerState,
    actions: PlayerActions,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(HifiColors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回主界面", color = HifiAmber, fontSize = 13.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HifiDashboardScreen(state, actions)
        }
    }
}

@Composable
private fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onMenu: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Rounded.Menu, contentDescription = "菜单", tint = AppText)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, color = AppMuted, fontSize = 11.sp)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun AppBottomBar(page: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Color(0xF21E1E1E), tonalElevation = 0.dp) {
        val items = listOf(
            Triple("主页", Icons.Rounded.Home, 0),
            Triple("曲库", Icons.Rounded.LibraryMusic, 1),
            Triple("投送", Icons.Rounded.Cast, 2),
        )
        items.forEach { (label, icon, index) ->
            NavigationBarItem(
                selected = page == index,
                onClick = { onSelect(index) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AppGold,
                    selectedTextColor = AppGold,
                    unselectedIconColor = AppDim,
                    unselectedTextColor = AppDim,
                    indicatorColor = AppGold.copy(alpha = 0.12f),
                ),
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    state: PlayerState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = AppSurface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(9.dp))
                    .background(coverBrush(0)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.trackName,
                    color = AppText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.paused) "已暂停" else "正在播放",
                    color = AppMuted,
                    fontSize = 10.sp,
                )
            }
            IconButton(onClick = actions.togglePause) {
                Icon(
                    if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = if (state.paused) "继续" else "暂停",
                    tint = AppGold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 主页
// ---------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    state: PlayerState,
    actions: PlayerActions,
    dlna: DlnaController,
    positionMs: Long,
    durationMs: Long,
    onMenu: () -> Unit,
) {
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val castActive = dlna.casting
    val shownPositionMs = if (castActive) dlna.castPositionMs else (dragPositionMs ?: positionMs)
    val shownDurationMs = if (castActive) dlna.castDurationMs else durationMs
    val displayTitle = when {
        castActive -> dlna.castTitle.ifBlank { "正在投送" }
        state.trackName.isBlank() -> "未选择音频"
        else -> state.trackName
    }
    val displaySubtitle = if (castActive) {
        "投送到 ${dlna.selectedRenderer?.friendlyName ?: "PC"} · PC 端实时状态"
    } else {
        subtitleFor(state.currentPath)
    }
    val nowCover = rememberNowPlayingCover(state, dlna)
    val fraction = if (shownDurationMs > 0) {
        (shownPositionMs.toFloat() / shownDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    // A queue only exists for album playback or a NAS catalog queue. A single
    // picked file has no previous/next target, so those controls are disabled.
    val catalogQueueActive = state.catalogQueueIndex in state.catalogQueue.indices
    val queueSize = if (catalogQueueActive) state.catalogQueue.size else state.tracks.size
    val canSkip = !castActive && queueSize > 0
    val canShuffle = !castActive && queueSize > 1
    val canRepeat = !castActive && (state.playing || state.paused)
    val shuffleActive = state.playMode == PlayMode.Shuffle
    val repeatActive = state.playMode == PlayMode.Loop
    // The play button must reflect every source that can actually be resumed:
    // NAS catalog queue, the current local file, or the manual path field.
    // Checking only filePath previously made NAS/test/polarity sources look
    // unloaded and turned the button grey after returning from the advanced
    // page even though a track was still loaded.
    val canResumeCatalog = state.catalogQueueIndex in state.catalogQueue.indices
    val canResumeCurrent = remember(state.currentPath, state.playing, state.paused) {
        state.currentPath.isNotBlank() &&
            !state.currentPath.startsWith("http") &&
            runCatching { File(state.currentPath).isFile }.getOrDefault(false)
    }
    val canStartPath = remember(state.filePath, state.playing, state.paused) {
        state.filePath.isNotBlank() &&
            runCatching { File(state.filePath).isFile }.getOrDefault(false)
    }
    val playEnabled = !castActive && (
        state.playing || state.paused || canResumeCatalog || canResumeCurrent || canStartPath
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        AppTopBar(
            title = "正在播放",
            subtitle = if (castActive) "投送中" else sourceLabel(state),
            onMenu = onMenu,
            trailing = {
                CastChip(dlna)
            },
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(26.dp)),
        ) {
            if (nowCover != null) {
                Image(
                    bitmap = nowCover,
                    contentDescription = "正在播放封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(coverBrush(0)),
                ) {
                    Box(
                        Modifier.align(Alignment.Center).size(92.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                    )
                }
            }
            Text(
                when {
                    castActive -> "投送中 · ${dlna.castState.ifBlank { "已连接" }}"
                    state.playing || state.paused ->
                        "源 ${state.sourceRate} Hz · 输出 ${state.outputRate} Hz"
                    else -> "等待播放"
                },
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
                modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            displayTitle,
            color = AppText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            displaySubtitle,
            color = AppMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (castActive) {
                Chip("投送中", AppBlue)
                Chip(dlna.selectedRenderer?.friendlyName ?: "PC", AppBlue)
                Chip(
                    when (dlna.castState) {
                        "PLAYING" -> "播放中"
                        "PAUSED_PLAYBACK" -> "已暂停"
                        "TRANSITIONING" -> "缓冲中"
                        else -> "已连接"
                    },
                    AppBlue,
                )
            } else {
                Chip(
                    sourceLabel(state),
                    if (state.playing || state.paused) AppGreen else AppDim,
                )
                Chip("输出 ${state.outputRate} Hz", AppBlue)
                if (state.processingActive) Chip("DSP", AppGold)
            }
        }
        if (!castActive && !state.playing && !state.paused) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = actions.pickAudio,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppGold,
                        contentColor = Color(0xFF1A1408),
                    ),
                ) {
                    Text("选择音频文件", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = actions.pickAudioFolder,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("选择文件夹", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (castActive) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)),
                color = AppBlue,
                trackColor = AppSurface2,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(shownPositionMs), color = AppDim, fontSize = 10.sp)
                Text(formatMs(shownDurationMs), color = AppDim, fontSize = 10.sp)
            }
        } else if (shownDurationMs > 0) {
            Slider(
                value = shownPositionMs.toFloat().coerceIn(0f, shownDurationMs.toFloat()),
                onValueChange = { dragPositionMs = it.toLong() },
                onValueChangeFinished = {
                    dragPositionMs?.let { NativeBridge.seekPlayback(it) }
                    dragPositionMs = null
                },
                valueRange = 0f..shownDurationMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = AppGold,
                    activeTrackColor = AppGold,
                    inactiveTrackColor = AppSurface2,
                ),
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(shownPositionMs), color = AppDim, fontSize = 10.sp)
                Text(formatMs(shownDurationMs), color = AppDim, fontSize = 10.sp)
            }
        } else {
            LinearProgressIndicator(
                progress = { 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)),
                color = AppGold,
                trackColor = AppSurface2,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    actions.setPlayMode(
                        if (shuffleActive) PlayMode.Sequential else PlayMode.Shuffle,
                    )
                },
                enabled = canShuffle,
            ) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = if (shuffleActive) "关闭随机" else "随机",
                    tint = when {
                        !canShuffle -> AppDim.copy(alpha = 0.35f)
                        shuffleActive -> AppGold
                        else -> AppMuted
                    },
                )
            }
            IconButton(onClick = actions.playPrevious, enabled = canSkip) {
                Icon(
                    Icons.Rounded.SkipPrevious,
                    contentDescription = "上一首",
                    tint = if (canSkip) AppMuted else AppDim.copy(alpha = 0.35f),
                )
            }
            Surface(
                onClick = {
                    if (state.playing || state.paused) {
                        actions.togglePause()
                    } else {
                        actions.resumePlayback()
                    }
                },
                modifier = Modifier.size(64.dp),
                enabled = playEnabled,
                shape = CircleShape,
                color = if (playEnabled) AppGold else AppSurface2,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            castActive -> Icons.Rounded.Cast
                            state.playing && !state.paused -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (castActive) {
                            "投送中，请在投送页控制"
                        } else {
                            "播放/暂停"
                        },
                        tint = if (playEnabled) Color(0xFF1A1408) else AppDim,
                        modifier = Modifier.size(if (castActive) 26.dp else 30.dp),
                    )
                }
            }
            IconButton(onClick = actions.playNext, enabled = canSkip) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = "下一首",
                    tint = if (canSkip) AppMuted else AppDim.copy(alpha = 0.35f),
                )
            }
            IconButton(
                onClick = {
                    actions.setPlayMode(
                        if (repeatActive) PlayMode.Sequential else PlayMode.Loop,
                    )
                },
                enabled = canRepeat,
            ) {
                Icon(
                    Icons.Rounded.Repeat,
                    contentDescription = if (repeatActive) "关闭循环" else "循环",
                    tint = when {
                        !canRepeat -> AppDim.copy(alpha = 0.35f)
                        repeatActive -> AppGold
                        else -> AppMuted
                    },
                )
            }
        }
        if (castActive) {
            Text(
                "投送控制请到“投送”页或 DLNA 设置页操作",
                color = AppMuted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CastChip(dlna: DlnaController) {
    val text = when {
        dlna.casting -> "投送到 ${dlna.selectedRenderer?.friendlyName ?: "PC"}"
        dlna.dmrRunning -> "接收中"
        else -> "未投送"
    }
    val color = when {
        dlna.casting -> AppBlue
        dlna.dmrRunning -> AppGreen
        else -> AppDim
    }
    Chip(text, color)
}

// ---------------------------------------------------------------------------
// 曲库
// ---------------------------------------------------------------------------

@Composable
private fun LibraryScreen(
    state: PlayerState,
    dlna: DlnaController,
    onMenu: () -> Unit,
    onPlayTrack: (AlbumItem, Int) -> Unit,
    onPlayCatalogTrack: (CatalogTrack, List<CatalogTrack>, Int) -> Unit,
    onCastCatalogTrack: (CatalogTrack) -> Unit,
) {
    var source by remember { mutableStateOf(0) }
    var openAlbum by remember { mutableStateOf<AlbumItem?>(null) }
    var openCatalogAlbum by remember { mutableStateOf<CatalogAlbum?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sources = listOf("本地", "NAS")
    val localAlbums = state.albums
    val localCovers = remember { mutableStateMapOf<String, ImageBitmap>() }

    val catalogStore = remember { CatalogSettingsStore(context) }
    var catalogSettings by remember { mutableStateOf(catalogStore.load()) }
    var showCatalogSettings by remember { mutableStateOf(false) }
    // NAS playback/casting needs both the SQL catalog and WebDAV credentials;
    // without WebDAV the play and cast buttons must stay disabled.
    var nasStreamReady by remember {
        mutableStateOf(
            runCatching { WebDavCredentialStore(context).load() != null }
                .getOrDefault(false),
        )
    }
    val castReady = nasStreamReady &&
        dlna.dmcEnabled &&
        dlna.selectedRenderer != null &&
        dlna.dmsRunning
    // Debug-only UI verification hook: open the NAS/SQL dialog from an intent
    // extra, because MIUI blocks adb input injection on the test device.
    val activityForDebug = context as? android.app.Activity
    LaunchedEffect(Unit) {
        val debuggable =
            (context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable &&
            activityForDebug?.intent?.getBooleanExtra("open_nas_settings", false) == true
        ) {
            showCatalogSettings = true
        }
    }
    val catalogClient = remember(catalogSettings) {
        if (catalogSettings.baseUrl.isNotBlank() && catalogSettings.token.isNotBlank()) {
            CatalogClient(catalogSettings.baseUrl, catalogSettings.token)
        } else {
            null
        }
    }
    DisposableEffect(catalogClient) {
        onDispose { catalogClient?.close() }
    }
    var catalogAlbums by remember { mutableStateOf<List<CatalogAlbum>>(emptyList()) }
    val catalogCovers = remember { mutableStateMapOf<String, ImageBitmap>() }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogLoading by remember { mutableStateOf(false) }
    var catalogReload by remember { mutableStateOf(0) }
    // Tracks which reload counter produced the current list, so "刷新" really
    // refetches instead of being ignored once albums are non-empty.
    var loadedReload by remember { mutableStateOf(-1) }

    LaunchedEffect(source, catalogClient, catalogReload) {
        if (source == 1 && catalogClient != null && loadedReload != catalogReload) {
            loadedReload = catalogReload
            catalogLoading = true
            catalogError = null
            try {
                runCatching { catalogClient.listAlbums() }
                    .onSuccess { catalogAlbums = it }
                    .onFailure { catalogError = it.message ?: "曲库连接失败" }
            } finally {
                catalogLoading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "曲库",
            subtitle = "本地与 NAS 统一浏览",
            onMenu = onMenu,
            trailing = {
                // Search is not implemented yet: keep it visibly disabled so it
                // no longer looks like a clickable no-op.
                IconButton(onClick = {}, enabled = false) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = "搜索（暂未开放）",
                        tint = AppDim.copy(alpha = 0.35f),
                    )
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEachIndexed { index, label ->
                FilterChip(
                    selected = source == index,
                    onClick = { source = index },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppSurface2,
                        selectedLabelColor = AppText,
                        labelColor = AppMuted,
                        containerColor = AppSurface,
                    ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (source) {
            0 -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(localAlbums) { index, album ->
                    LaunchedEffect(album.relativePath) {
                        if (localCovers[album.relativePath] == null) {
                            val bitmap = withContext(Dispatchers.IO) {
                                loadLocalCover(state.musicDir, album.relativePath)
                            }
                            if (bitmap != null) {
                                localCovers[album.relativePath] = bitmap.asImageBitmap()
                            }
                        }
                    }
                    AlbumTile(
                        name = album.name,
                        meta = "${album.tracks.size} 首 · 本地",
                        index = index,
                        cover = localCovers[album.relativePath],
                        onClick = { openAlbum = album },
                    )
                }
            }

            else -> Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (catalogClient == null) "NAS 曲库未配置"
                        else "NAS 曲库 · ${catalogAlbums.size} 张",
                        color = AppMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showCatalogSettings = true }) {
                        Text("配置", color = AppGold, fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = { catalogReload++ },
                        enabled = catalogClient != null && !catalogLoading,
                    ) {
                        Text(
                            if (catalogError != null) "重试" else "刷新",
                            color = if (catalogClient != null && !catalogLoading) {
                                AppMuted
                            } else {
                                AppDim.copy(alpha = 0.35f)
                            },
                            fontSize = 11.sp,
                        )
                    }
                }

                when {
                    catalogClient == null -> {
                        Text(
                            "点击“配置”填写 SQL API 地址与密码；保存后会自动加载专辑与封面。",
                            color = AppMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    catalogLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(24.dp).size(28.dp),
                            color = AppGold,
                            strokeWidth = 2.dp,
                        )
                    }
                    catalogError != null -> {
                        Text(
                            "加载失败：${catalogError}",
                            color = AppMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(catalogAlbums, key = { _, album -> album.id }) { index, album ->
                            LaunchedEffect(album.id) {
                                if (catalogCovers[album.id] == null) {
                                    val bitmap = withContext(Dispatchers.IO) {
                                        val bytes = catalogClient.loadCover(album)
                                        if (bytes != null) {
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        } else {
                                            null
                                        }
                                    }
                                    if (bitmap != null) {
                                        catalogCovers[album.id] = bitmap.asImageBitmap()
                                    }
                                }
                            }
                            AlbumTile(
                                name = album.title,
                                meta = listOfNotNull(
                                    album.artist.takeIf { it.isNotBlank() },
                                    "${album.trackCount} 首",
                                ).joinToString(" · "),
                                index = index,
                                cover = catalogCovers[album.id],
                                onClick = { openCatalogAlbum = album },
                            )
                        }
                    }
                }
            }
        }
    }

    openAlbum?.let { album ->
        AlbumTracksDialog(
            album = album,
            onDismiss = { openAlbum = null },
            onPlay = { index ->
                openAlbum = null
                onPlayTrack(album, index)
            },
        )
    }

    openCatalogAlbum?.let { album ->
        CatalogAlbumDialog(
            album = album,
            client = catalogClient,
            nasStreamReady = nasStreamReady,
            castReady = castReady,
            onDismiss = { openCatalogAlbum = null },
            onPlay = { track, queue, index ->
                openCatalogAlbum = null
                onPlayCatalogTrack(track, queue, index)
            },
            onCast = onCastCatalogTrack,
        )
    }

    if (showCatalogSettings) {
        CatalogSettingsDialog(
            initialUrl = catalogSettings.baseUrl,
            initialPassword = catalogSettings.token,
            onDismiss = { showCatalogSettings = false },
            onSave = { url, password ->
                catalogStore.save(url, password)
                catalogSettings = catalogStore.load()
                nasStreamReady = runCatching {
                    WebDavCredentialStore(context).load() != null
                }.getOrDefault(false)
                catalogAlbums = emptyList()
                catalogCovers.clear()
                catalogError = null
                catalogReload++
                showCatalogSettings = false
            },
        )
    }
}

@Composable
private fun CatalogSettingsDialog(
    initialUrl: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var password by remember { mutableStateOf(initialPassword) }
    var showPassword by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val savedWebDav = remember { WebDavCredentialStore(context).load() }
    var webDavUrl by remember {
        mutableStateOf(savedWebDav?.account?.baseUrl?.toString().orEmpty())
    }
    var webDavUser by remember {
        mutableStateOf(savedWebDav?.account?.username.orEmpty())
    }
    var webDavPassword by remember { mutableStateOf(savedWebDav?.password.orEmpty()) }
    var webDavAuth by remember { mutableStateOf(savedWebDav?.authType ?: WebDavAuthType.AUTO) }
    var showWebDavPassword by remember { mutableStateOf(false) }
    var webDavError by remember { mutableStateOf<String?>(null) }
    val exportScope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }

    fun exportCredentials() {
        exportScope.launch {
            val result = withContext(Dispatchers.IO) {
                NasCredentialExporter.export(context)
            }
            exportMessage = if (result.ok) {
                "已导出到：${result.path}\n文件为明文，请勿分享。"
            } else {
                "导出失败：${result.error}"
            }
        }
    }

    fun save() {
        webDavError = null
        val hasWebDav = webDavUrl.isNotBlank() ||
            webDavUser.isNotBlank() ||
            webDavPassword.isNotEmpty()
        if (hasWebDav) {
            if (webDavUser.isBlank() || webDavPassword.isEmpty()) {
                webDavError = "填写 WebDAV 需同时填写地址、用户名和密码"
                return
            }
            val base = try {
                WebDavUrl.parseBase(webDavUrl)
            } catch (e: Exception) {
                webDavError = e.message ?: "WebDAV 地址格式不正确"
                return
            }
            try {
                WebDavCredentialStore(context).save(
                    WebDavAccount(base, webDavUser.trim()),
                    webDavPassword,
                    webDavAuth,
                )
            } catch (e: Exception) {
                webDavError = e.message ?: "WebDAV 凭据保存失败"
                return
            }
        }
        onSave(url, password)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            color = AppSurface,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
        ) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "NAS / SQL 曲库",
                        color = AppText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { exportCredentials() }) {
                        Text("导出凭据", color = AppGold, fontSize = 11.sp)
                    }
                }
                Text(
                    "填写 SQL API 地址与密码；留空密码则保留原值。",
                    color = AppMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "导出的是已保存的凭据；修改后请先保存。",
                    color = AppMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                exportMessage?.let { message ->
                    Text(
                        message,
                        color = AppMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("API 地址（http://NAS-IP:9007）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "隐藏" else "显示", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "WebDAV（NAS 音频拉流）",
                    color = AppText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "本地播放与投送 NAS 曲目需要 WebDAV；只改 SQL Token 时可留空。",
                    color = AppMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = webDavUrl,
                    onValueChange = { webDavUrl = it },
                    label = { Text("WebDAV 地址（http://NAS-IP:9005/）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = webDavUser,
                    onValueChange = { webDavUser = it },
                    label = { Text("WebDAV 用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = webDavPassword,
                    onValueChange = { webDavPassword = it },
                    label = { Text("WebDAV 密码") },
                    singleLine = true,
                    visualTransformation = if (showWebDavPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { showWebDavPassword = !showWebDavPassword }) {
                            Text(if (showWebDavPassword) "隐藏" else "显示", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WebDavAuthType.values().forEach { type ->
                        FilterChip(
                            selected = webDavAuth == type,
                            onClick = { webDavAuth = type },
                            label = { Text(type.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppSurface2,
                                selectedLabelColor = AppText,
                                labelColor = AppMuted,
                                containerColor = AppSurface,
                            ),
                        )
                    }
                }
                webDavError?.let { message ->
                    Text(
                        message,
                        color = Color(0xFFEF5350),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(Modifier.padding(top = 12.dp)) {
                    Button(
                        onClick = { save() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppGold,
                            contentColor = Color(0xFF1A1408),
                        ),
                    ) { Text("保存", fontSize = 12.sp) }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = onDismiss) { Text("取消", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun AlbumTracksDialog(
    album: AlbumItem,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            color = AppSurface,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(album.name, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${album.tracks.size} 首 · 本地文件",
                    color = AppMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.padding(vertical = 12.dp)) {
                    Button(
                        onClick = { onPlay(0) },
                        enabled = album.tracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppGold,
                            contentColor = Color(0xFF1A1408),
                        ),
                    ) {
                        Text("播放全部", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = onDismiss) {
                        Text("关闭", fontSize = 12.sp)
                    }
                }
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                ) {
                    album.tracks.forEachIndexed { index, track ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPlay(index) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                (index + 1).toString().padStart(2, '0'),
                                color = AppDim,
                                fontSize = 10.sp,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                track.name,
                                color = AppText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(color = AppBorder.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogAlbumDialog(
    album: CatalogAlbum,
    client: CatalogClient?,
    nasStreamReady: Boolean,
    castReady: Boolean,
    onDismiss: () -> Unit,
    onPlay: (CatalogTrack, List<CatalogTrack>, Int) -> Unit,
    onCast: (CatalogTrack) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var detail by remember { mutableStateOf<CatalogAlbumDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(album.id, client) {
        if (client == null) {
            error = "曲库未配置"
            loading = false
            return@LaunchedEffect
        }
        runCatching { client.getAlbum(album.id) }
            .onSuccess { detail = it }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }
    val played = remember(detail) { PlayedTrackStore(context).load() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
            color = AppSurface,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(album.title, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        album.artist.takeIf { it.isNotBlank() },
                        "${album.trackCount} 首",
                    ).joinToString(" · "),
                    color = AppMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier.padding(24.dp).size(28.dp),
                        color = AppGold,
                        strokeWidth = 2.dp,
                    )
                    error != null -> {
                        Text(
                            "加载失败：$error",
                            color = AppMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        OutlinedButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
                    }
                    detail != null -> {
                        val tracks = detail!!.tracks
                        Row(Modifier.padding(vertical = 12.dp)) {
                            Button(
                                onClick = { tracks.firstOrNull()?.let { onPlay(it, tracks, 0) } },
                                enabled = tracks.isNotEmpty() && nasStreamReady,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppGold,
                                    contentColor = Color(0xFF1A1408),
                                ),
                            ) {
                                Text("播放全部", fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            OutlinedButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
                        }
                        if (!nasStreamReady) {
                            Text(
                                "未配置 NAS WebDAV：请先在“配置”中填写 WebDAV 地址、用户名和密码，播放才可用。",
                                color = AppMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        if (!castReady) {
                            Text(
                                "投送需先在“投送”页选择设备并启动曲库共享。",
                                color = AppMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Column(
                            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        ) {
                            tracks.forEachIndexed { index, track ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = nasStreamReady) {
                                            onPlay(track, tracks, index)
                                        }
                                        .padding(vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        (index + 1).toString().padStart(2, '0'),
                                        color = AppDim,
                                        fontSize = 10.sp,
                                        modifier = Modifier.width(28.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            track.title,
                                            color = if (track.id in played) AppDim else AppText,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${track.format.uppercase()} · " +
                                                formatMs(track.durationMs.toLong()) +
                                                if (track.id in played) " · 已播" else "",
                                            color = AppDim,
                                            fontSize = 10.sp,
                                        )
                                    }
                                    TextButton(
                                        onClick = { onCast(track) },
                                        enabled = castReady,
                                    ) {
                                        Text(
                                            "投送",
                                            color = if (castReady) {
                                                AppBlue
                                            } else {
                                                AppDim.copy(alpha = 0.35f)
                                            },
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                                HorizontalDivider(color = AppBorder.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(
    name: String,
    meta: String,
    index: Int,
    cover: ImageBitmap?,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        val shape = RoundedCornerShape(16.dp)
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(shape),
            )
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
                    .background(coverBrush(index)),
            )
        }
        Text(
            name,
            color = AppText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            meta,
            color = AppDim,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun loadLocalCover(musicDir: String, relativePath: String): Bitmap? =
    loadCoverFromDirectory(File(musicDir, relativePath))

@Composable
private fun rememberNowPlayingCover(state: PlayerState, dlna: DlnaController): ImageBitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { CatalogSettingsStore(context).load() }
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(
        state.currentPath,
        state.trackName,
        state.catalogQueueIndex,
        dlna.casting,
        dlna.castAlbumId,
    ) {
        cover = null
        val path = state.currentPath
        val castAlbumId = if (dlna.casting) dlna.castAlbumId else null
        if (path.isBlank() && castAlbumId == null) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            when {
                castAlbumId != null ->
                    loadCatalogCover(settings.baseUrl, settings.token, castAlbumId)
                path.startsWith("http") -> {
                    val track = state.catalogQueue.getOrNull(state.catalogQueueIndex)
                    track?.let {
                        loadCatalogCover(settings.baseUrl, settings.token, it.albumId)
                    }
                }
                else -> loadCoverFromFile(File(path))
            }
        }
        if (bitmap != null) {
            cover = bitmap.asImageBitmap()
        }
    }
    return cover
}

private suspend fun loadCatalogCover(baseUrl: String, token: String, albumId: String): Bitmap? {
    if (baseUrl.isBlank() || token.isBlank()) return null
    val client = CatalogClient(baseUrl, token)
    return try {
        val detail = client.getAlbum(albumId)
        val bytes = client.loadCover(detail.album)
        if (bytes != null) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        client.close()
    }
}

private fun loadCoverFromDirectory(dir: File): Bitmap? {
    val candidates = listOf(
        "cover.jpg", "folder.jpg", "front.jpg", "album.jpg",
        "cover.png", "folder.png", "front.png",
    )
    for (name in candidates) {
        val file = File(dir, name)
        if (file.isFile) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
    }
    return null
}

private fun loadCoverFromFile(file: File): Bitmap? {
    file.parentFile?.let { dir ->
        loadCoverFromDirectory(dir)?.let { return it }
    }
    return loadEmbeddedCover(file.absolutePath)
}

private fun loadEmbeddedCover(path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.embeddedPicture?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

// ---------------------------------------------------------------------------
// 投送
// ---------------------------------------------------------------------------

@Composable
private fun CastScreen(dlna: DlnaController, onMenu: () -> Unit) {
    val scope = rememberCoroutineScope()
    val roles = listOf(DlnaRole.SENDER, DlnaRole.RECEIVER, DlnaRole.OFFLINE)
    LaunchedEffect(Unit) { dlna.refreshNetwork() }
    val hasNetwork = dlna.networkAddress != null
    val isSender = dlna.role == DlnaRole.SENDER
    val isReceiver = dlna.role == DlnaRole.RECEIVER
    val canDiscover = isSender && dlna.dmcEnabled &&
        !dlna.dmcDiscovering && hasNetwork
    val canToggleDms = isSender && dlna.dmsEnabled && !dlna.dmsBusy &&
        (dlna.dmsRunning || hasNetwork)
    val canToggleDmr = isReceiver && !dlna.dmrBusy &&
        (dlna.dmrRunning || hasNetwork)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        AppTopBar(title = "投送", subtitle = "DLNA 发送 / 接收", onMenu = onMenu)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            roles.forEach { role ->
                FilterChip(
                    selected = dlna.role == role,
                    onClick = { scope.launch { dlna.selectRole(role) } },
                    label = { Text(role.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppSurface2,
                        selectedLabelColor = AppText,
                        labelColor = AppMuted,
                        containerColor = AppSurface,
                    ),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("当前投送目标", color = AppDim, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            dlna.selectedRenderer?.friendlyName ?: "未选择设备",
                            color = AppText,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            dlna.selectedRenderer?.location?.host ?: "请先发现并选择渲染器",
                            color = AppMuted,
                            fontSize = 10.sp,
                        )
                    }
                    Chip(
                        if (dlna.selectedRenderer != null) "已连接" else "未选择",
                        if (dlna.selectedRenderer != null) AppGreen else AppDim,
                    )
                }
            }
        }

        if (dlna.casting) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("正在投送", color = AppDim, fontSize = 10.sp)
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                .background(coverBrush(0)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                dlna.castTitle.ifBlank { "投送中" },
                                color = AppText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatMs(dlna.castPositionMs)} / ${formatMs(dlna.castDurationMs)}",
                                color = AppMuted,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = { scope.launch { dlna.pauseOrResumeCast() } }) {
                            Text(if (dlna.castPaused) "继续" else "暂停", fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { scope.launch { dlna.stopCast() } }) {
                            Text("停止", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("发现设备", color = AppDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { scope.launch { dlna.discoverRenderers() } },
                        enabled = canDiscover,
                    ) {
                        Text(
                            if (dlna.dmcDiscovering) "发现中…" else "重新发现",
                            color = if (canDiscover) {
                                AppBlue
                            } else {
                                AppDim.copy(alpha = 0.35f)
                            },
                            fontSize = 11.sp,
                        )
                    }
                }
                if (!isSender || !dlna.dmcEnabled || !hasNetwork) {
                    Text(
                        when {
                            !isSender -> "切换到“发送者”角色后可用"
                            !dlna.dmcEnabled -> "投送端已关闭，请到 DLNA 设置中开启"
                            else -> "未连接局域网，无法发现设备"
                        },
                        color = AppMuted,
                        fontSize = 10.sp,
                    )
                }
                if (dlna.renderers.isEmpty()) {
                    Text(
                        "尚未发现 MediaRenderer",
                        color = AppMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    dlna.renderers.forEachIndexed { index, renderer ->
                        if (index > 0) HorizontalDivider(color = AppBorder)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                dlna.selectRenderer(renderer)
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Devices, contentDescription = null, tint = AppGreen)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(renderer.friendlyName, color = AppText, fontSize = 12.sp)
                                Text(renderer.location.host, color = AppDim, fontSize = 10.sp)
                            }
                            if (dlna.selectedRenderer == renderer) {
                                Chip("已选中", AppGold)
                            }
                        }
                    }
                }
                dlna.dmcError?.let { message ->
                    Text(
                        message,
                        color = Color(0xFFEF5350),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("曲库共享（DMS）", color = AppDim, fontSize = 10.sp)
                        Text(
                            dlna.dmsStatus.ifBlank {
                                if (dlna.dmsRunning) "共享中" else "未启动"
                            },
                            color = AppMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                if (dlna.dmsRunning) dlna.stopDms() else dlna.startDms()
                            }
                        },
                        enabled = canToggleDms,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppGold,
                            contentColor = Color(0xFF1A1408),
                        ),
                    ) {
                        Text(if (dlna.dmsRunning) "停止" else "启动", fontSize = 11.sp)
                    }
                }
                if (!canToggleDms) {
                    Text(
                        when {
                            !isSender -> "切换到“发送者”角色后可用"
                            !dlna.dmsEnabled -> "曲库共享已关闭，请到 DLNA 设置中开启"
                            !hasNetwork -> "未连接局域网，无法启动曲库共享"
                            else -> "处理中…"
                        },
                        color = AppMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                dlna.dmsError?.let { message ->
                    Text(
                        message,
                        color = Color(0xFFEF5350),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (dlna.role == DlnaRole.RECEIVER) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("接收投送（DMR）", color = AppDim, fontSize = 10.sp)
                        Text(
                            dlna.dmrStatus.ifBlank { if (dlna.dmrRunning) "接收中" else "未启动" },
                            color = AppMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                if (dlna.dmrRunning) dlna.stopDmr() else dlna.startDmr()
                            }
                        },
                        enabled = canToggleDmr,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppGold,
                            contentColor = Color(0xFF1A1408),
                        ),
                    ) {
                        Text(if (dlna.dmrRunning) "停止接收" else "启动接收", fontSize = 11.sp)
                    }
                }
                if (!canToggleDmr) {
                    Text(
                        if (!hasNetwork) "未连接局域网，无法启动接收" else "处理中…",
                        color = AppMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                dlna.dmrError?.let { message ->
                    Text(
                        message,
                        color = Color(0xFFEF5350),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// 抽屉
// ---------------------------------------------------------------------------

@Composable
private fun AppDrawer(
    state: PlayerState,
    actions: PlayerActions,
    versionName: String,
    onOpenAdvancedAudio: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(vertical = 20.dp)) {
        Text(
            "老石头",
            color = AppGold,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "$versionName · UI 重构预览",
            color = AppDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(18.dp))
        DrawerSection("音频")
        DrawerItem(
            label = "输出设备",
            trailing = "${state.outputRate} Hz",
            enabled = false,
            onClick = onClose,
        )
        DrawerItem(label = "EQ / DSP", enabled = false, onClick = onClose)
        DrawerItem(label = "高级音频设置") {
            onClose()
            onOpenAdvancedAudio()
        }
        DrawerItem(
            label = "专注播放",
            trailing = "未开放",
            enabled = false,
            onClick = onClose,
        )
        DrawerSection("服务")
        DrawerItem(label = "DLNA 设置") {
            onClose()
            actions.setDlnaOpen(true)
        }
        DrawerSection("其他")
        DrawerItem(label = "关于") {
            onClose()
            actions.setAboutOpen(true)
        }
        DrawerItem(
            label = "帮助",
            trailing = "未开放",
            enabled = false,
            onClick = onClose,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "UI 壳子预览 · 播放逻辑未改动",
            color = AppDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(
        title,
        color = AppDim,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun DrawerItem(
    label: String,
    trailing: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) AppMuted else AppDim.copy(alpha = 0.35f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                color = if (enabled) AppDim else AppDim.copy(alpha = 0.35f),
                fontSize = 10.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

@Composable
private fun Chip(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(AppSurface)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = AppMuted, fontSize = 10.sp)
    }
}

private fun sourceLabel(state: PlayerState): String =
    when {
        state.currentPath.startsWith("http") -> "NAS · 流媒体"
        state.playing || state.paused -> "本地文件"
        else -> "未播放"
    }

private fun subtitleFor(path: String): String {
    if (path.isBlank()) return "选择一首歌开始播放"
    return if (path.startsWith("http")) {
        "NAS 流媒体"
    } else {
        val file = File(path)
        file.parentFile?.name ?: file.name
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = (ms / 1000).toInt()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
