// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns

internal fun yesNo(value: Boolean): String = if (value) "是" else "否"

internal fun resamplerQualityLabel(quality: Int): String = when (quality) {
    1 -> "BEST"
    2 -> "MEDIUM"
    3 -> "FASTEST"
    else -> "自动"
}

data class PlaybackStartResult(val started: Boolean, val status: String)

fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment ?: "所选音频文件"
}

fun startPlayback(
    context: Context,
    path: String,
    displayName: String,
    processing: Boolean,
    eqPath: String,
    crossfeed: Boolean,
    eqCascade: Boolean,
    eqUseSlope: Boolean,
    eqSlope: Double,
    forcedOutputRate: Int,
    resamplerQuality: Int,
    crossfeedWidth: Double,
    stereoWidth: Double,
    loudnessEnabled: Boolean,
    loudnessVolume: Double,
    limiterEnabled: Boolean,
    limiterThresholdDb: Double,
    autoUsb192k: Boolean,
    brirPath: String,
    brirEnabled: Boolean,
    brirMix: Double,
): PlaybackStartResult {
    val audioManager = context.getSystemService(AudioManager::class.java)
    val nativeRate = audioManager
        ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        ?.toIntOrNull()
        ?: 0
    val supportedRates = audioManager
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.flatMap { it.sampleRates.toList() }
        ?.distinct()
        ?.sorted()
        ?.toIntArray()
        ?: intArrayOf()

    val usbDetected = audioManager
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.any { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        ?: false

    val started = runCatching {
        NativeBridge.startFile(
            path,
            processing,
            supportedRates,
            nativeRate,
            eqPath,
            crossfeed,
            eqCascade,
            eqUseSlope,
            eqSlope,
            forcedOutputRate,
            resamplerQuality,
            crossfeedWidth,
            stereoWidth,
            loudnessEnabled,
            loudnessVolume,
            limiterEnabled,
            limiterThresholdDb,
            usbDetected,
            autoUsb192k,
            brirPath,
            brirEnabled,
            brirMix,
        )
    }.getOrElse { error ->
        return PlaybackStartResult(false, "播放失败：${error.message}")
    }

    if (!started) {
        return PlaybackStartResult(false, "打开/播放文件失败")
    }

    runCatching { PlaybackService.start(context, displayName) }

    val status = buildString {
        appendLine("正在播放：$displayName")
        appendLine("处理模式：${yesNo(NativeBridge.playbackProcessingMode())}")
        appendLine("EQ：${NativeBridge.playbackEqBandCount()} 段")
        appendLine("交叉馈送：${yesNo(NativeBridge.playbackCrossfeedEnabled())}")
        appendLine("限制器：${yesNo(NativeBridge.playbackLimiterEnabled())}")
        appendLine("EQ 级联：${if (eqCascade) "4 阶" else "2 阶"}")
        appendLine("搁架斜率：${if (eqUseSlope) "S=$eqSlope" else "Q"}")
        appendLine("采样率覆盖：${if (forcedOutputRate > 0) "$forcedOutputRate Hz" else "自动"}")
        appendLine("重采样质量：${resamplerQualityLabel(resamplerQuality)}")
        appendLine("Crossfeed 强度：${"%.0f".format(crossfeedWidth * 100)}%")
        appendLine("声场宽度：${"%.0f".format(stereoWidth * 100)}%")
        appendLine("响度补偿：${yesNo(loudnessEnabled)}")
        appendLine(
            "限制器阈值：" + String.format(java.util.Locale.US, "%.1f", limiterThresholdDb) +
                " dBFS" + if (limiterEnabled) "" else "（已关闭）",
        )
        appendLine(
            "USB 自动 192k：${yesNo(autoUsb192k)}" +
                "（检测到 USB：${yesNo(usbDetected)}）",
        )
        appendLine(
            "BRIR：" + if (brirEnabled && brirPath.isNotEmpty()) {
                "${brirPath.substringAfterLast('/')}，" +
                    String.format(java.util.Locale.US, "%.0f%%", brirMix * 100)
            } else {
                "关闭"
            },
        )
        appendLine("源采样率：${NativeBridge.playbackSourceRate()} Hz")
        appendLine("目标采样率：${NativeBridge.playbackTargetRate()} Hz")
        appendLine("实际输出：${NativeBridge.playbackActualRate()} Hz")
        appendLine("跨采样率族：${yesNo(NativeBridge.playbackCrossFamily())}")
        appendLine("非整数倍转换：${yesNo(NativeBridge.playbackNonInteger())}")
        appendLine("受设备限制：${yesNo(NativeBridge.playbackDeviceLimited())}")
        appendLine("独占模式：${yesNo(NativeBridge.playbackExclusive())}")
        append("欠载次数：${NativeBridge.playbackUnderruns()}")
    }
    return PlaybackStartResult(true, status)
}
