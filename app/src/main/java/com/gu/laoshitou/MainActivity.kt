// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.gu.laoshitou.ui.HifiDashboardScreen
import com.gu.laoshitou.ui.ImmersivePlayerScreen
import com.gu.laoshitou.ui.MinimalPlayerScreen
import com.gu.laoshitou.ui.AboutScreen
import com.gu.laoshitou.ui.AlbumItem
import com.gu.laoshitou.ui.EqEditorFrequencies
import com.gu.laoshitou.ui.IrItem
import com.gu.laoshitou.ui.EqEditorQ
import com.gu.laoshitou.ui.PlayMode
import com.gu.laoshitou.ui.PlayerActions
import com.gu.laoshitou.ui.PlayerState
import com.gu.laoshitou.ui.SoundPreset
import com.gu.laoshitou.ui.TrackItem
import com.gu.laoshitou.ui.UiVersion
import com.gu.laoshitou.ui.eqEditorMaxDb
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            AppRoot()
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val defaultPath = remember {
        val directory = context.getExternalFilesDir(null)?.absolutePath
        if (directory.isNullOrEmpty()) {
            "/sdcard/Android/data/com.gu.laoshitou/files/test.wav"
        } else {
            "$directory/test.wav"
        }
    }

    val defaultEqPath = remember {
        val directory = context.getExternalFilesDir(null)?.absolutePath
        if (directory.isNullOrEmpty()) {
            "/sdcard/Android/data/com.gu.laoshitou/files/eq.txt"
        } else {
            "$directory/eq.txt"
        }
    }

    val summary = remember {
        runCatching {
            val ffmpeg = NativeBridge.ffmpegVersion()
            val processing = NativeBridge.sampleRatePlan(
                sourceRate = 44100,
                processing = true,
                supportedRates = intArrayOf(
                    44100,
                    48000,
                    88200,
                    96000,
                    176400,
                    192000,
                ),
                nativeRate = 48000,
            )
            val direct = NativeBridge.sampleRatePlan(
                sourceRate = 44100,
                processing = false,
                supportedRates = intArrayOf(44100, 48000),
                nativeRate = 48000,
            )
            "FFmpeg 版本：$ffmpeg\n" +
                "处理模式（44.1k 源）：$processing Hz\n" +
                "直通模式（44.1k 源）：$direct Hz"
        }.getOrElse { error ->
            "原生库错误：${error.message}"
        }
    }

    val musicDir = remember {
        val base = context.getExternalFilesDir(null)
            ?: File("/sdcard/Android/data/com.gu.laoshitou/files")
        File(base, "Music").apply { mkdirs() }.absolutePath
    }

    val settings = remember {
        context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
    }
    val state = remember {
        PlayerState(summary, musicDir, defaultPath, defaultEqPath).apply {
            outputRateOverride = settings.getInt("output_rate", 0)
            resamplerQuality = settings.getInt("resampler_quality", 0)
            usbAuto192k = settings.getBoolean("usb_auto_192k", true)
            eqEditorGains = parsePersistedGains(
                settings.getString("eq_editor_gains", null),
            )
            eqEditorPreamp = settings.getFloat("eq_editor_preamp", 0f).toDouble()
            brirEnabled = settings.getBoolean("brir_enabled", false)
            brirPath = settings.getString("brir_path", "") ?: ""
            brirName = settings.getString("brir_name", "") ?: ""
            brirMix = settings.getFloat("brir_mix", 0.35f).toDouble()
        }
    }
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
    }

    fun currentLoudnessVolume(enabled: Boolean): Double {
        if (!enabled || audioManager == null) {
            return 1.0
        }
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maximum > 0) {
            current.toDouble() / maximum.toDouble()
        } else {
            1.0
        }
    }

    fun saveBrirSettings() {
        settings.edit()
            .putBoolean("brir_enabled", state.brirEnabled)
            .putString("brir_path", state.brirPath)
            .putString("brir_name", state.brirName)
            .putFloat("brir_mix", state.brirMix.toFloat())
            .apply()
    }

    fun refreshIrFiles() {
        scope.launch {
            val directory = File(context.getExternalFilesDir(null), "IR").apply { mkdirs() }
            val files = withContext(Dispatchers.IO) {
                directory.listFiles { file ->
                    file.isFile && file.extension.equals("wav", true)
                }?.sortedBy { it.name.lowercase() } ?: emptyList()
            }
            state.irFiles = files.map { file ->
                IrItem(file.nameWithoutExtension, file.absolutePath)
            }
            if (state.brirPath.isNotEmpty() &&
                files.none { it.absolutePath == state.brirPath }
            ) {
                state.brirEnabled = false
                state.brirPath = ""
                state.brirName = ""
                saveBrirSettings()
            }
        }
    }

    fun writePresetFile(name: String, content: String): String {
        val directory = File(context.getExternalFilesDir(null), "EQ").apply { mkdirs() }
        val file = File(directory, name)
        file.writeText(content)
        return file.absolutePath
    }

    fun applyEqEditor() {
        val text = buildVisualEqProfileText(state.eqEditorGains, state.eqEditorPreamp)
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                writePresetFile("visual_16band.txt", text)
            }
            state.eqPath = path
            state.eqName = "可视化 16 段"
            state.status = "已应用 16 段 EQ（下次播放生效）"
            settings.edit()
                .putString("eq_editor_gains", state.eqEditorGains.joinToString(","))
                .putFloat("eq_editor_preamp", state.eqEditorPreamp.toFloat())
                .putBoolean("use_visual_eq", true)
                .apply()
        }
    }

    fun applyPresetState(
        preset: SoundPreset,
        eqPath: String,
        eqName: String,
        crossfeed: Boolean,
        crossfeedWidth: Double,
        stereoWidth: Double,
        loudness: Boolean,
    ) {
        state.eqPath = eqPath
        state.eqName = eqName
        state.crossfeed = crossfeed
        state.crossfeedWidth = crossfeedWidth
        state.stereoWidth = stereoWidth
        state.loudness = loudness
        state.status = "已应用预设：${preset.label}（EQ/声场下次播放生效）"
        settings.edit().putBoolean("use_visual_eq", false).apply()
    }

    fun applyPreset(preset: SoundPreset) {
        when (preset) {
            SoundPreset.Studio -> applyPresetState(
                preset, "", "", false, 0.0, 1.0, false,
            )
            SoundPreset.Live -> applyPresetState(
                preset, "", "", true, 0.85, 1.25, false,
            )
            SoundPreset.NightIem -> {
                val text = """
                    Preamp: -2.0 dB
                    Filter 1: ON HPF Fc 20 Hz Q 0.707
                    Filter 2: ON PK Fc 3000 Hz Gain -2.0 dB Q 1.0
                """.trimIndent() + "\n"
                scope.launch {
                    val path = withContext(Dispatchers.IO) {
                        writePresetFile("night_iem.txt", text)
                    }
                    applyPresetState(
                        preset, path, "深夜动铁（内置）", true, 0.30, 1.0, true,
                    )
                }
            }
            SoundPreset.Nearfield -> {
                val text = """
                    Preamp: -2.0 dB
                    Filter 1: ON LSC Fc 80 Hz Gain 2.0 dB Q 0.7
                    Filter 2: ON HSC Fc 10000 Hz Gain -1.0 dB Q 0.7
                """.trimIndent() + "\n"
                scope.launch {
                    val path = withContext(Dispatchers.IO) {
                        writePresetFile("nearfield.txt", text)
                    }
                    applyPresetState(
                        preset, path, "近场书架（内置）", true, 0.50, 1.10, false,
                    )
                }
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDisplayName(context, uri)
            scope.launch {
                state.status = "正在准备文件..."
                val extension = displayName
                    .substringAfterLast('.', "")
                    .filter { it.isLetterOrDigit() }
                    .take(8)
                val cacheFile = File(
                    context.cacheDir,
                    if (extension.isEmpty()) {
                        "selected_audio"
                    } else {
                        "selected_audio.$extension"
                    },
                )
                val result = withContext(Dispatchers.IO) {
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: return@withContext PlaybackStartResult(
                                false,
                                "无法读取所选文件",
                            )
                        input.use { source ->
                            cacheFile.outputStream().use { target ->
                                source.copyTo(target)
                            }
                        }
                        startPlayback(
                            context = context,
                            path = cacheFile.absolutePath,
                            displayName = displayName,
                            processing = state.processing,
                            eqPath = state.eqPath,
                            crossfeed = state.crossfeed,
                            eqCascade = state.eqCascade,
                            eqUseSlope = state.eqSlopeEnabled,
                            eqSlope = state.eqSlope,
                            forcedOutputRate = state.outputRateOverride,
                            resamplerQuality = state.resamplerQuality,
                            crossfeedWidth = state.crossfeedWidth,
                            stereoWidth = state.stereoWidth,
                            loudnessEnabled = state.loudness,
                            loudnessVolume = currentLoudnessVolume(state.loudness),
                            limiterEnabled = state.limiterEnabled,
            limiterThresholdDb = state.limiterThresholdDb,
            autoUsb192k = state.usbAuto192k,
            brirPath = state.brirPath,
            brirEnabled = state.brirEnabled,
            brirMix = state.brirMix,
                        )
                    } catch (error: Exception) {
                        PlaybackStartResult(false, "读取文件失败：${error.message}")
                    }
                }
                state.playing = result.started
                state.paused = false
                state.status = result.status
                if (result.started) {
                    state.trackName = displayName
                    state.tracks = emptyList()
                    state.currentTrackIndex = -1
                    state.currentPath = cacheFile.absolutePath
                }
                refreshMetrics(state)
            }
        }
    }

    val eqPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDisplayName(context, uri)
            scope.launch {
                state.status = "正在准备 EQ 配置..."
                val cached = withContext(Dispatchers.IO) {
                    try {
                        val extension = displayName
                            .substringAfterLast('.', "")
                            .lowercase()
                            .filter { it.isLetterOrDigit() }
                            .take(8)
                        val cacheFile = File(
                            context.cacheDir,
                            if (extension.isEmpty()) {
                                "eq_profile.txt"
                            } else {
                                "eq_profile.$extension"
                            },
                        )
                        val input = context.contentResolver.openInputStream(uri)
                            ?: return@withContext null
                        input.use { source ->
                            cacheFile.outputStream().use { target ->
                                source.copyTo(target)
                            }
                        }
                        cacheFile
                    } catch (error: Exception) {
                        null
                    }
                }
                if (cached == null) {
                    state.status = "读取 EQ 配置失败"
                } else {
                    state.eqPath = cached.absolutePath
                    state.eqName = displayName
                    state.status = "已选择 EQ 配置：$displayName（下次播放生效）"
                    settings.edit().putBoolean("use_visual_eq", false).apply()
                }
            }
        }
    }

    val brirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDisplayName(context, uri)
            scope.launch {
                state.status = "正在准备 BRIR..."
                val target = withContext(Dispatchers.IO) {
                    try {
                        val directory = File(context.getExternalFilesDir(null), "IR")
                            .apply { mkdirs() }
                        val safeName = displayName
                            .substringAfterLast('/')
                            .ifBlank { "brir.wav" }
                        val file = File(directory, safeName)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: return@withContext null
                        input.use { source ->
                            file.outputStream().use { output ->
                                source.copyTo(output)
                            }
                        }
                        file
                    } catch (error: Exception) {
                        null
                    }
                }
                if (target == null) {
                    state.status = "读取 BRIR 文件失败"
                } else {
                    state.brirPath = target.absolutePath
                    state.brirName = target.name
                    state.brirEnabled = true
                    saveBrirSettings()
                    refreshIrFiles()
                    state.status = "已选择 BRIR：${target.name}（下次播放生效）"
                }
            }
        }
    }

    fun refreshLibrary() {
        scope.launch {
            val folders = withContext(Dispatchers.IO) {
                scanMusicLibrary(File(state.musicDir))
            }
            state.albums = folders.map { folder ->
                AlbumItem(
                    name = folder.name,
                    relativePath = folder.relativePath,
                    tracks = folder.files.map { file ->
                        TrackItem(
                            path = file.absolutePath,
                            name = file.nameWithoutExtension,
                        )
                    },
                )
            }
            if (state.selectedAlbumPath != null &&
                state.albums.none { it.relativePath == state.selectedAlbumPath }
            ) {
                state.selectedAlbumPath = null
            }
            if (state.currentTrackIndex >= state.tracks.size) {
                state.currentTrackIndex = -1
            }
            val trackCount = state.albums.sumOf { it.tracks.size }
            state.status = if (state.albums.isEmpty()) {
                "音乐文件夹为空：${state.musicDir}"
            } else {
                "已扫描 ${state.albums.size} 张专辑，$trackCount 首音乐"
            }
        }
    }

    fun beginPlayback(path: String, displayName: String, trackIndex: Int): Boolean {
        val result = startPlayback(
            context = context,
            path = path,
            displayName = displayName,
            processing = state.processing,
            eqPath = state.eqPath,
            crossfeed = state.crossfeed,
            eqCascade = state.eqCascade,
            eqUseSlope = state.eqSlopeEnabled,
            eqSlope = state.eqSlope,
            forcedOutputRate = state.outputRateOverride,
            resamplerQuality = state.resamplerQuality,
            crossfeedWidth = state.crossfeedWidth,
            stereoWidth = state.stereoWidth,
            loudnessEnabled = state.loudness,
            loudnessVolume = currentLoudnessVolume(state.loudness),
            limiterEnabled = state.limiterEnabled,
            limiterThresholdDb = state.limiterThresholdDb,
            autoUsb192k = state.usbAuto192k,
            brirPath = state.brirPath,
            brirEnabled = state.brirEnabled,
            brirMix = state.brirMix,
        )
        state.playing = result.started
        state.paused = false
        state.status = result.status
        if (result.started) {
            state.trackName = displayName
            state.currentTrackIndex = trackIndex
            state.currentPath = path
        }
        refreshMetrics(state)
        return result.started
    }

    fun playTrack(index: Int) {
        val album = state.selectedAlbum ?: return
        val track = album.tracks.getOrNull(index) ?: return
        state.tracks = album.tracks
        state.filePath = track.path
        beginPlayback(track.path, track.name, index)
    }

    fun startPolarityTest() {
        scope.launch {
            state.status = "正在生成声道/极性测试..."
            val file = withContext(Dispatchers.IO) {
                writePolarityTestWav(context)
            }
            val result = startPlayback(
                context = context,
                path = file.absolutePath,
                displayName = "声道/极性测试",
                processing = false,
                eqPath = "",
                crossfeed = false,
                eqCascade = false,
                eqUseSlope = false,
                eqSlope = 1.0,
                forcedOutputRate = 0,
                resamplerQuality = 0,
                crossfeedWidth = 0.0,
                stereoWidth = 1.0,
                loudnessEnabled = false,
                loudnessVolume = 1.0,
                limiterEnabled = false,
                limiterThresholdDb = -1.0,
                autoUsb192k = false,
                brirPath = "",
                brirEnabled = false,
                brirMix = 0.0,
            )
            state.playing = result.started
            state.paused = false
            state.status = if (result.started) {
                "声道/极性测试：左 → 右 → 同相 → 反相。反相应居中、低频更少。"
            } else {
                result.status
            }
            if (result.started) {
                state.trackName = "声道/极性测试"
                state.tracks = emptyList()
                state.currentTrackIndex = -1
                state.currentPath = file.absolutePath
            }
            refreshMetrics(state)
        }
    }

    fun advanceAfterEnd(): Boolean {
        val current = state.currentTrackIndex
        if (current < 0) {
            if (state.playMode == PlayMode.Loop && state.currentPath.isNotEmpty()) {
                return beginPlayback(state.currentPath, state.trackName, -1)
            }
            return false
        }

        val count = state.tracks.size
        if (count == 0) {
            return false
        }
        val next = when (state.playMode) {
            PlayMode.Sequential -> current + 1
            PlayMode.Loop -> (current + 1) % count
            PlayMode.Shuffle -> {
                if (count == 1) {
                    current
                } else {
                    var candidate = Random.nextInt(count - 1)
                    if (candidate >= current) {
                        candidate += 1
                    }
                    candidate
                }
            }
        }
        if (next !in state.tracks.indices) {
            return false
        }
        playTrack(next)
        return state.playing
    }

    val actions = PlayerActions(
        pickAudio = { audioPicker.launch(arrayOf("audio/*")) },
        pickEq = { eqPicker.launch(arrayOf("*/*")) },
        startPath = {
            val displayName = state.filePath.substringAfterLast('/').ifBlank {
                state.filePath
            }
            val album = state.albums.firstOrNull { candidate ->
                candidate.tracks.any { it.path == state.filePath }
            }
            if (album == null) {
                state.tracks = emptyList()
                beginPlayback(state.filePath, displayName, -1)
            } else {
                state.tracks = album.tracks
                val index = album.tracks.indexOfFirst { it.path == state.filePath }
                beginPlayback(state.filePath, displayName, index)
            }
        },
        startTestTone = {
            val started = NativeBridge.startTestTone(48000)
            state.playing = started
            state.paused = false
            state.trackName = "440 Hz 测试音"
            state.status = if (started) {
                "正在播放 440 Hz 测试音"
            } else {
                "启动 Oboe 音频流失败"
            }
            refreshMetrics(state)
        },
        stop = {
            NativeBridge.stopPlayback()
            context.stopService(Intent(context, PlaybackService::class.java))
            state.playing = false
            state.paused = false
            state.cpuPercent = 0f
            state.status = "已停止"
        },
        togglePause = {
            if (state.paused) {
                NativeBridge.resumePlayback()
            } else {
                NativeBridge.pausePlayback()
            }
            state.paused = !state.paused
        },
        setProcessing = { state.processing = it },
        setCrossfeed = { enabled ->
            state.crossfeed = enabled
            if (enabled && state.crossfeedWidth <= 0.0) {
                state.crossfeedWidth = 0.33
            }
        },
        setCrossfeedWidth = { width ->
            state.crossfeedWidth = width
            state.crossfeed = width > 0.0
        },
        setStereoWidth = { state.stereoWidth = it },
        setLoudness = { state.loudness = it },
        setLimiterEnabled = { state.limiterEnabled = it },
        setLimiterThresholdDb = {
            state.limiterThresholdDb = it.coerceIn(-24.0, 0.0)
        },
        pickBrir = { brirPicker.launch(arrayOf("*/*")) },
        clearBrir = {
            state.brirEnabled = false
            state.brirPath = ""
            state.brirName = ""
            saveBrirSettings()
            state.status = "已关闭 BRIR（下次播放生效）"
        },
        setBrirEnabled = { enabled ->
            if (enabled && state.brirPath.isEmpty()) {
                state.status = "请先选择或放入 BRIR WAV 文件"
            } else {
                state.brirEnabled = enabled
                saveBrirSettings()
            }
        },
        setBrirMix = {
            state.brirMix = it.coerceIn(0.0, 1.0)
            saveBrirSettings()
        },
        refreshIrFiles = ::refreshIrFiles,
        selectIr = { path, name ->
            state.brirPath = path
            state.brirName = name
            state.brirEnabled = true
            saveBrirSettings()
            state.status = "已选择 BRIR：$name（下次播放生效）"
        },
        applyPreset = ::applyPreset,
        clearEq = {
            state.eqPath = ""
            state.eqName = ""
            state.status = "已清除 EQ 配置（下次播放生效）"
            settings.edit().putBoolean("use_visual_eq", false).apply()
        },
        setEqCascade = { state.eqCascade = it },
        setEqSlopeEnabled = { state.eqSlopeEnabled = it },
        setEqSlope = { state.eqSlope = it },
        setOutputRateOverride = {
            state.outputRateOverride = it
            settings.edit().putInt("output_rate", it).apply()
        },
        setResamplerQuality = {
            state.resamplerQuality = it
            settings.edit().putInt("resampler_quality", it).apply()
        },
        setUsbAuto192k = {
            state.usbAuto192k = it
            settings.edit().putBoolean("usb_auto_192k", it).apply()
        },
        setEqEditorGain = { index, gain ->
            val updated = state.eqEditorGains.toMutableList()
            if (index in updated.indices) {
                updated[index] = gain.coerceIn(-18f, 18f)
                state.eqEditorGains = updated
            }
        },
        setEqEditorPreamp = {
            state.eqEditorPreamp = it.coerceIn(-24.0, 12.0)
        },
        autoEqEditorPreamp = {
            state.eqEditorPreamp = (-eqEditorMaxDb(state.eqEditorGains))
                .coerceIn(-24.0, 12.0)
        },
        applyEqEditor = ::applyEqEditor,
        resetEqEditor = {
            state.eqEditorGains = List(EqEditorFrequencies.size) { 0f }
            state.eqEditorPreamp = 0.0
        },
        saveEqEditor = {
            settings.edit()
                .putString("eq_editor_gains", state.eqEditorGains.joinToString(","))
                .putFloat("eq_editor_preamp", state.eqEditorPreamp.toFloat())
                .apply()
        },
        loadCurrentEqIntoEditor = {
            val path = state.eqPath
            if (path.isEmpty()) {
                state.status = "当前没有 EQ 配置文件"
            } else {
                scope.launch {
                    val values = withContext(Dispatchers.IO) {
                        NativeBridge.loadEqProfileForEditor(
                            path,
                            if (state.eqCascade) 2 else 1,
                            state.eqSlopeEnabled,
                            state.eqSlope,
                        )
                    }
                    if (values.size >= EqEditorFrequencies.size + 1) {
                        state.eqEditorGains = values
                            .take(EqEditorFrequencies.size)
                            .map { it.coerceIn(-18f, 18f) }
                        state.eqEditorPreamp = values
                            .last()
                            .toDouble()
                            .coerceIn(-24.0, 12.0)
                        settings.edit()
                            .putString(
                                "eq_editor_gains",
                                state.eqEditorGains.joinToString(","),
                            )
                            .putFloat(
                                "eq_editor_preamp",
                                state.eqEditorPreamp.toFloat(),
                            )
                            .apply()
                        state.status = "已载入当前 EQ 文件到 16 段编辑器（应用后生效）"
                    } else {
                        state.status = "解析当前 EQ 文件失败"
                    }
                }
            }
        },
        startPolarityTest = ::startPolarityTest,
        setPlayMode = { state.playMode = it },
        setUiVersion = { state.uiVersion = it },
        setAboutOpen = { state.aboutOpen = it },
        setFilePath = { state.filePath = it },
        setEqPath = { state.eqPath = it },
        refreshLibrary = ::refreshLibrary,
        openAlbum = { relativePath -> state.selectedAlbumPath = relativePath },
        closeAlbum = { state.selectedAlbumPath = null },
        playTrack = ::playTrack,
    )

    LaunchedEffect(Unit) {
        refreshIrFiles()
        if (settings.getBoolean("use_visual_eq", false)) {
            val text = buildVisualEqProfileText(
                state.eqEditorGains,
                state.eqEditorPreamp,
            )
            val path = withContext(Dispatchers.IO) {
                writePresetFile("visual_16band.txt", text)
            }
            state.eqPath = path
            state.eqName = "可视化 16 段"
        }
        refreshLibrary()
    }

    LaunchedEffect(state.playing) {
        var lastCpuMs = Process.getElapsedCpuTime()
        var lastWallMs = SystemClock.elapsedRealtime()
        while (state.playing) {
            if (!NativeBridge.isPlaying()) {
                state.playing = false
                state.paused = false
                state.cpuPercent = 0f
                state.status = "已停止"
                break
            }
            if (NativeBridge.playbackNeedsRecovery()) {
                val recovered = withContext(Dispatchers.Default) {
                    NativeBridge.recoverPlayback()
                }
                if (recovered) {
                    state.status = "检测到音频设备变化，输出已重新打开"
                }
            }
            refreshMetrics(state)
            NativeBridge.setLoudnessVolume(
                if (state.loudness) currentLoudnessVolume(true).toFloat() else 1.0f,
            )
            val cpuMs = Process.getElapsedCpuTime()
            val wallMs = SystemClock.elapsedRealtime()
            if (wallMs > lastWallMs) {
                state.cpuPercent =
                    ((cpuMs - lastCpuMs) * 100.0 / (wallMs - lastWallMs)).toFloat()
                lastCpuMs = cpuMs
                lastWallMs = wallMs
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                state.thermalStatus = powerManager?.currentThermalStatus ?: 0
            }
            state.metrics = "实时：输出 ${state.outputRate} Hz，" +
                "欠载 ${state.underruns}，" +
                "独占 ${yesNo(state.exclusive)}" +
                (if (state.paused) "，已暂停" else "") +
                (if (state.ended) "，已结束" else "")

            if (state.ended) {
                if (advanceAfterEnd()) {
                    delay(500)
                    continue
                }
                NativeBridge.stopPlayback()
                context.stopService(Intent(context, PlaybackService::class.java))
                state.playing = false
                state.paused = false
                state.cpuPercent = 0f
                state.status = "播放结束"
                break
            }
            delay(500)
        }
    }

    if (state.aboutOpen) {
        AboutScreen(
            versionName = versionName,
            onClose = { state.aboutOpen = false },
        )
    } else {
        when (state.uiVersion) {
            UiVersion.Minimal -> MinimalPlayerScreen(state, actions)
            UiVersion.Immersive -> ImmersivePlayerScreen(state, actions)
            UiVersion.Hifi -> HifiDashboardScreen(state, actions)
        }
    }
}

private fun parsePersistedGains(text: String?): List<Float> {
    if (text.isNullOrEmpty()) {
        return List(EqEditorFrequencies.size) { 0f }
    }
    val parts = text.split(',')
    return List(EqEditorFrequencies.size) { index ->
        parts.getOrNull(index)?.toFloatOrNull()?.coerceIn(-18f, 18f) ?: 0f
    }
}

private fun buildVisualEqProfileText(gains: List<Float>, preamp: Double): String {
    return buildString {
        append("Preamp: ")
        append(String.format(Locale.US, "%.1f", preamp))
        append(" dB\n")
        EqEditorFrequencies.forEachIndexed { index, frequency ->
            append("Filter ${index + 1}: ON PK Fc ")
            append(frequency.toInt())
            append(" Hz Gain ")
            append(
                String.format(
                    Locale.US,
                    "%.1f",
                    gains.getOrElse(index) { 0f },
                ),
            )
            append(" dB Q ")
            append(String.format(Locale.US, "%.2f", EqEditorQ))
            append("\n")
        }
    }
}

private fun writePolarityTestWav(context: Context): File {
    val sampleRate = 48000
    val segmentSeconds = 2
    val framesPerSegment = sampleRate * segmentSeconds
    val segments = listOf(
        1 to 0,
        0 to 1,
        1 to 1,
        1 to -1,
    )
    val totalFrames = framesPerSegment * segments.size
    val dataBytes = totalFrames * 4
    val file = File(context.cacheDir, "polarity_test.wav")

    file.outputStream().buffered().use { output ->
        fun writeLe16(value: Int) {
            output.write(value and 0xFF)
            output.write((value shr 8) and 0xFF)
        }

        fun writeLe32(value: Int) {
            output.write(value and 0xFF)
            output.write((value shr 8) and 0xFF)
            output.write((value shr 16) and 0xFF)
            output.write((value shr 24) and 0xFF)
        }

        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(36 + dataBytes)
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLe32(16)
        writeLe16(1)
        writeLe16(2)
        writeLe32(sampleRate)
        writeLe32(sampleRate * 4)
        writeLe16(4)
        writeLe16(16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeLe32(dataBytes)

        for ((leftSign, rightSign) in segments) {
            for (frame in 0 until framesPerSegment) {
                val seconds = frame.toDouble() / sampleRate.toDouble()
                val sample = (0.1 * sin(2.0 * PI * 1000.0 * seconds) * 32767.0).toInt()
                writeLe16(sample * leftSign)
                writeLe16(sample * rightSign)
            }
        }
    }
    return file
}

private fun refreshMetrics(state: PlayerState) {
    state.sourceRate = NativeBridge.playbackSourceRate()
    state.targetRate = NativeBridge.playbackTargetRate()
    state.outputRate = NativeBridge.playbackActualRate()
    state.exclusive = NativeBridge.playbackExclusive()
    state.crossFamily = NativeBridge.playbackCrossFamily()
    state.nonInteger = NativeBridge.playbackNonInteger()
    state.deviceLimited = NativeBridge.playbackDeviceLimited()
    state.underruns = NativeBridge.playbackUnderruns()
    state.eqBands = NativeBridge.playbackEqBandCount()
    state.processingActive = NativeBridge.playbackProcessingMode()
    state.crossfeedActive = NativeBridge.playbackCrossfeedEnabled()
    state.limiterActive = NativeBridge.playbackLimiterEnabled()
    state.brirActive = NativeBridge.playbackBrirActive()
    state.paused = NativeBridge.isPlaybackPaused()
    state.ended = NativeBridge.playbackEnded()
}
