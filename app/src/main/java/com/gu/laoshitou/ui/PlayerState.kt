// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class UiVersion(val label: String) {
    Minimal("极简"),
    Immersive("沉浸"),
    Hifi("高保真"),
}

enum class PlayMode(val label: String) {
    Sequential("顺序"),
    Loop("循环"),
    Shuffle("随机"),
}

enum class SoundPreset(val label: String) {
    Studio("录音棚"),
    NightIem("深夜动铁"),
    Nearfield("近场书架"),
    Live("现场 Live"),
}

data class TrackItem(
    val path: String,
    val name: String,
)

data class AlbumItem(
    val name: String,
    val relativePath: String,
    val tracks: List<TrackItem>,
)

data class IrItem(
    val name: String,
    val path: String,
)

class PlayerState(
    val summary: String,
    val musicDir: String,
    defaultFilePath: String,
    defaultEqPath: String,
) {
    var uiVersion by mutableStateOf(UiVersion.Minimal)
    var aboutOpen by mutableStateOf(false)

    var filePath by mutableStateOf(defaultFilePath)
    var currentPath by mutableStateOf("")
    var eqPath by mutableStateOf(defaultEqPath)
    var eqName by mutableStateOf("")
    var trackName by mutableStateOf("未选择音频")
    var albums by mutableStateOf<List<AlbumItem>>(emptyList())
    var selectedAlbumPath by mutableStateOf<String?>(null)
    var tracks by mutableStateOf<List<TrackItem>>(emptyList())
    var currentTrackIndex by mutableStateOf(-1)
    var playMode by mutableStateOf(PlayMode.Sequential)

    val selectedAlbum: AlbumItem?
        get() = albums.firstOrNull { it.relativePath == selectedAlbumPath }

    var processing by mutableStateOf(true)
    var crossfeed by mutableStateOf(true)
    var crossfeedWidth by mutableStateOf(0.33)
    var stereoWidth by mutableStateOf(1.0)
    var loudness by mutableStateOf(false)
    var limiterEnabled by mutableStateOf(true)
    var limiterThresholdDb by mutableStateOf(-1.0)
    var brirEnabled by mutableStateOf(false)
    var brirPath by mutableStateOf("")
    var brirName by mutableStateOf("")
    var brirMix by mutableStateOf(0.35)
    var brirActive by mutableStateOf(false)
    var irFiles by mutableStateOf<List<IrItem>>(emptyList())
    var eqCascade by mutableStateOf(false)
    var eqSlopeEnabled by mutableStateOf(false)
    var eqSlope by mutableStateOf(1.0)
    var outputRateOverride by mutableStateOf(0)
    var resamplerQuality by mutableStateOf(0)
    var usbAuto192k by mutableStateOf(true)
    var cpuPercent by mutableStateOf(0f)
    var thermalStatus by mutableStateOf(0)
    var eqEditorGains by mutableStateOf(List(EqEditorFrequencies.size) { 0f })
    var eqEditorPreamp by mutableStateOf(0.0)
    var playing by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var status by mutableStateOf("")
    var metrics by mutableStateOf("")

    var sourceRate by mutableStateOf(0)
    var targetRate by mutableStateOf(0)
    var outputRate by mutableStateOf(0)
    var exclusive by mutableStateOf(false)
    var crossFamily by mutableStateOf(false)
    var nonInteger by mutableStateOf(false)
    var deviceLimited by mutableStateOf(false)
    var underruns by mutableStateOf(0L)
    var eqBands by mutableStateOf(0)
    var processingActive by mutableStateOf(false)
    var crossfeedActive by mutableStateOf(false)
    var limiterActive by mutableStateOf(false)
    var ended by mutableStateOf(false)
}

data class PlayerActions(
    val pickAudio: () -> Unit,
    val pickEq: () -> Unit,
    val startPath: () -> Unit,
    val startTestTone: () -> Unit,
    val stop: () -> Unit,
    val togglePause: () -> Unit,
    val refreshLibrary: () -> Unit,
    val openAlbum: (String) -> Unit,
    val closeAlbum: () -> Unit,
    val playTrack: (Int) -> Unit,
    val setProcessing: (Boolean) -> Unit,
    val setCrossfeed: (Boolean) -> Unit,
    val setCrossfeedWidth: (Double) -> Unit,
    val setStereoWidth: (Double) -> Unit,
    val setLoudness: (Boolean) -> Unit,
    val setLimiterEnabled: (Boolean) -> Unit,
    val setLimiterThresholdDb: (Double) -> Unit,
    val pickBrir: () -> Unit,
    val clearBrir: () -> Unit,
    val setBrirEnabled: (Boolean) -> Unit,
    val setBrirMix: (Double) -> Unit,
    val refreshIrFiles: () -> Unit,
    val selectIr: (String, String) -> Unit,
    val applyPreset: (SoundPreset) -> Unit,
    val clearEq: () -> Unit,
    val setEqCascade: (Boolean) -> Unit,
    val setEqSlopeEnabled: (Boolean) -> Unit,
    val setEqSlope: (Double) -> Unit,
    val setOutputRateOverride: (Int) -> Unit,
    val setResamplerQuality: (Int) -> Unit,
    val setUsbAuto192k: (Boolean) -> Unit,
    val setEqEditorGain: (Int, Float) -> Unit,
    val setEqEditorPreamp: (Double) -> Unit,
    val autoEqEditorPreamp: () -> Unit,
    val applyEqEditor: () -> Unit,
    val resetEqEditor: () -> Unit,
    val saveEqEditor: () -> Unit,
    val loadCurrentEqIntoEditor: () -> Unit,
    val startPolarityTest: () -> Unit,
    val setPlayMode: (PlayMode) -> Unit,
    val setUiVersion: (UiVersion) -> Unit,
    val setAboutOpen: (Boolean) -> Unit,
    val setFilePath: (String) -> Unit,
    val setEqPath: (String) -> Unit,
)
