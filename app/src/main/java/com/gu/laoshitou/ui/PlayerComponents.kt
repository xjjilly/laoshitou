// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

internal fun playbackStateText(state: PlayerState): String = when {
    state.paused -> "已暂停"
    state.playing -> "正在播放"
    else -> "待播放"
}

@Composable
fun UiVersionSelector(
    selected: UiVersion,
    onSelect: (UiVersion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UiVersion.entries.forEach { version ->
            FilterChip(
                selected = selected == version,
                onClick = { onSelect(version) },
                label = {
                    Text(
                        text = version.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
fun MusicLibrary(
    state: PlayerState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val album = state.selectedAlbum
        if (album == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "音乐专辑（${state.albums.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.refreshLibrary) {
                    Text("刷新")
                }
            }
            PlayModeSelector(state, actions)
            Spacer(Modifier.height(8.dp))

            if (state.albums.isEmpty()) {
                Text(
                    text = "音乐文件夹：${state.musicDir}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "每个子文件夹是一张专辑；放入音频后点“刷新”。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                state.albums.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.openAlbum(item.relativePath) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${item.tracks.size} 首" +
                                    if (item.relativePath.isEmpty()) {
                                        " · 根目录"
                                    } else {
                                        " · ${item.relativePath}"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = actions.closeAlbum) {
                    Text("← 专辑")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${album.tracks.size} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = actions.refreshLibrary) {
                    Text("刷新")
                }
            }
            PlayModeSelector(state, actions)
            Spacer(Modifier.height(8.dp))

            album.tracks.forEachIndexed { index, track ->
                val selected = track.path == state.currentPath
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.playTrack(index) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selected) "▶" else "${index + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(36.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected) {
                            Text(
                                text = playbackStateText(state),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayModeSelector(state: PlayerState, actions: PlayerActions) {
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayMode.entries.forEach { mode ->
            FilterChip(
                selected = state.playMode == mode,
                onClick = { actions.setPlayMode(mode) },
                label = { Text(mode.label) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun PerformanceWarning(state: PlayerState, modifier: Modifier = Modifier) {
    if (!state.playing) {
        return
    }
    val cpuHigh = state.cpuPercent >= 100f
    val hasUnderruns = state.underruns > 0
    if (!cpuHigh && !hasUnderruns) {
        return
    }

    val cpuText = String.format(Locale.US, "%.0f%%", state.cpuPercent)
    val downsampling =
        state.sourceRate > 0 && state.outputRate > 0 &&
            state.outputRate < state.sourceRate
    val conversion = if (downsampling) {
        "，当前 ${state.sourceRate} → ${state.outputRate} Hz 为降采样"
    } else {
        ""
    }
    val suggestion = if (downsampling) {
        "建议把输出采样率设为 ${state.sourceRate}（原生），或把重采样质量改为 MEDIUM/FASTEST。"
    } else {
        "建议降低重采样质量或关闭部分 DSP。"
    }
    val underrunText = if (hasUnderruns) {
        "，欠载 ${state.underruns} 次"
    } else {
        ""
    }
    Text(
        text = if (cpuHigh) {
            "高负载警告：CPU $cpuText$conversion$underrunText。$suggestion 注意电量与发热。"
        } else {
            "欠载警告：已发生 ${state.underruns} 次欠载，可能出现杂音。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun QuickSettingsContent(
    state: PlayerState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text("音效与设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val eqLabel = state.eqName.ifEmpty {
            state.eqPath.substringAfterLast('/').ifEmpty { "未加载" }
        }
        Text("耳机校准：$eqLabel", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.pickEq, enabled = !state.playing) {
                Text("选择 AutoEQ 文件")
            }
            TextButton(onClick = actions.clearEq, enabled = !state.playing) {
                Text("清除")
            }
        }
        Text(
            "支持 AutoEQ ParametricEQ.txt / CSV / GraphicEQ；选择后下次播放生效。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitch(
            label = "响度补偿（随系统音量）",
            checked = state.loudness,
            onCheckedChange = actions.setLoudness,
            enabled = true,
        )
        Text(
            "音量越低，低频和高频补偿越多；播放中实时生效。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text("Crossfeed 强度", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = state.crossfeedWidth.toFloat(),
            onValueChange = { actions.setCrossfeedWidth(it.toDouble()) },
            valueRange = 0f..1f,
            enabled = !state.playing,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${(state.crossfeedWidth * 100).roundToInt()}%（0 = 关闭，下次播放生效）",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text("声场宽度（M/S）", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = state.stereoWidth.toFloat(),
            onValueChange = { actions.setStereoWidth(it.toDouble()) },
            valueRange = 0f..1.5f,
            enabled = !state.playing,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${(state.stereoWidth * 100).roundToInt()}%（100% = 原始，0% = 单声道）",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text("设备预设", style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SoundPreset.entries.forEach { preset ->
                FilterChip(
                    selected = false,
                    onClick = { actions.applyPreset(preset) },
                    label = { Text(preset.label) },
                    enabled = !state.playing,
                )
            }
        }
        Text(
            "预设会切换 EQ、Crossfeed 与声场宽度；EQ/声场在下次播放生效。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = actions.startPolarityTest,
            enabled = !state.playing,
        ) {
            Text("声道 / 极性测试")
        }
        Text(
            "左 → 右 → 同相 → 反相；反相应居中且低频更少。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun AdvancedSettingsContent(
    state: PlayerState,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text("高级设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(state.summary, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        SettingsSwitch(
            label = "处理模式（同族升频）",
            checked = state.processing,
            onCheckedChange = actions.setProcessing,
            enabled = !state.playing,
        )
        Text(
            "44.1/88.2 → 88.2/176.4；48/96 → 96/192；默认不会把 44.1 转成 96。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitch(
            label = "交叉馈送（耳机）",
            checked = state.crossfeed,
            onCheckedChange = actions.setCrossfeed,
            enabled = !state.playing,
        )
        if (state.crossfeed) {
            Text(
                "Crossfeed 强度：${(state.crossfeedWidth * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = state.crossfeedWidth.toFloat(),
                onValueChange = { actions.setCrossfeedWidth(it.toDouble()) },
                valueRange = 0f..1f,
                enabled = !state.playing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "EQ、交叉馈送、声场宽度、响度补偿和限制器仅在处理模式下生效。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "声场宽度（M/S）：${(state.stereoWidth * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = state.stereoWidth.toFloat(),
            onValueChange = { actions.setStereoWidth(it.toDouble()) },
            valueRange = 0f..1.5f,
            enabled = !state.playing,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitch(
            label = "响度补偿（随系统音量）",
            checked = state.loudness,
            onCheckedChange = actions.setLoudness,
            enabled = true,
        )
        Text(
            "音量越低，低频/高频补偿越多；播放中实时生效。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitch(
            label = "限制器",
            checked = state.limiterEnabled,
            onCheckedChange = actions.setLimiterEnabled,
            enabled = !state.playing,
        )
        if (state.limiterEnabled) {
            Text(
                "限制器阈值：" +
                    String.format(Locale.US, "%.1f", state.limiterThresholdDb) +
                    " dBFS",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = state.limiterThresholdDb.toFloat(),
                onValueChange = { actions.setLimiterThresholdDb(it.toDouble()) },
                valueRange = -12f..0f,
                enabled = !state.playing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "关闭限制器时，大电平或大幅 EQ 提升可能削波。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitch(
            label = "BRIR 卷积混响",
            checked = state.brirEnabled,
            onCheckedChange = actions.setBrirEnabled,
            enabled = !state.playing,
        )
        Text(
            "将立体声 BRIR WAV 放入应用文件目录的 IR/ 后点刷新，或直接选择文件；" +
                "设置重启后保留。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.irFiles.forEach { ir ->
                FilterChip(
                    selected = state.brirPath == ir.path,
                    onClick = { actions.selectIr(ir.path, ir.name) },
                    label = { Text(ir.name) },
                    enabled = !state.playing,
                )
            }
            TextButton(
                onClick = actions.refreshIrFiles,
                enabled = !state.playing,
            ) {
                Text("刷新")
            }
        }
        Text(
            "当前 BRIR：" + state.brirName.ifEmpty { "未选择" },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions.pickBrir,
                enabled = !state.playing,
            ) {
                Text("选择 BRIR WAV")
            }
            TextButton(
                onClick = actions.clearBrir,
                enabled = !state.playing,
            ) {
                Text("清除")
            }
        }
        if (state.brirEnabled) {
            Text(
                "湿声比例：" + (state.brirMix * 100).roundToInt() + "%",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = state.brirMix.toFloat(),
                onValueChange = { actions.setBrirMix(it.toDouble()) },
                valueRange = 0f..1f,
                enabled = !state.playing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "BRIR 采用分区 FFT 卷积，开启后约有 43 ms 延迟；建议与 Crossfeed 分别 A/B。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitch(
            label = "EQ 4 阶级联（两级 biquad）",
            checked = state.eqCascade,
            onCheckedChange = actions.setEqCascade,
            enabled = !state.playing,
        )
        Text(
            "每级增益减半、总增益不变；搁架与峰值滤波都会更陡。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitch(
            label = "搁架使用斜率 S（LSC/HSC）",
            checked = state.eqSlopeEnabled,
            onCheckedChange = actions.setEqSlopeEnabled,
            enabled = !state.playing,
        )
        if (state.eqSlopeEnabled) {
            Slider(
                value = state.eqSlope.toFloat(),
                onValueChange = { actions.setEqSlope(it.toDouble()) },
                valueRange = 0.1f..1.0f,
                enabled = !state.playing,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                String.format(
                    java.util.Locale.US,
                    "S = %.2f（1.0 最陡，越小越平缓）",
                    state.eqSlope,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "关闭时使用配置文件中的 Q；EQ 阶数与斜率在下次播放时生效。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("输出采样率（USB / 小尾巴）", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 176.4k/384k are intentionally not offered: the Android USB
            // audio route on the test device accepts up to 192k and a failed
            // 384k open can reset the USB audio device. The native layer still
            // accepts other rates for future devices.
            val rateOptions = listOf(
                0 to "自动",
                44100 to "44.1k",
                48000 to "48k",
                96000 to "96k",
                192000 to "192k",
            )
            rateOptions.forEach { (rate, label) ->
                FilterChip(
                    selected = state.outputRateOverride == rate,
                    onClick = { actions.setOutputRateOverride(rate) },
                    label = { Text(label) },
                    enabled = !state.playing,
                )
            }
        }
        Text(
            "自动使用系统报告的支持率；强制值可能被系统/设备重采样，实际输出见高保真界面。",
            style = MaterialTheme.typography.bodySmall,
        )
        SettingsSwitch(
            label = "USB DAC 自动 192k",
            checked = state.usbAuto192k,
            onCheckedChange = actions.setUsbAuto192k,
            enabled = !state.playing,
        )
        Text(
            "检测到 USB DAC 时，48k 族音源优先 192k；44.1k 族和手动覆盖不受影响。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text("重采样质量", style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val qualityOptions = listOf(
                0 to "自动",
                1 to "BEST",
                2 to "MEDIUM",
                3 to "FASTEST",
            )
            qualityOptions.forEach { (quality, label) ->
                FilterChip(
                    selected = state.resamplerQuality == quality,
                    onClick = { actions.setResamplerQuality(quality) },
                    label = { Text(label) },
                    enabled = !state.playing,
                )
            }
        }
        Text(
            "自动：PCM ≤192k 用 BEST，DSD/更高采样率用 MEDIUM；也可手动强制。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.filePath,
            onValueChange = actions.setFilePath,
            label = { Text("音频文件路径（手动）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        Text(
            "也可以将音频推送到该路径：adb push <文件> ${state.filePath}",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.eqPath,
            onValueChange = actions.setEqPath,
            label = { Text("EQ 配置路径（AutoEQ txt / CSV）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        Text(
            "支持 ParametricEQ.txt / FixedBandEQ.txt、GraphicEQ 行，" +
                "以及含 frequency,equalization/error/gain 列的 CSV；" +
                "CSV 可选 Slope 与 Order（1/2）列。" +
                "推送：adb push <文件> ${state.eqPath}",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = actions.pickEq, enabled = !state.playing) {
            Text("选择 EQ 配置")
        }
        Text(
            if (state.eqName.isEmpty()) {
                "当前未加载 EQ 配置"
            } else {
                "当前 EQ：${state.eqName}"
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = actions.startPath,
                enabled = !state.playing,
                modifier = Modifier.weight(1f),
            ) {
                Text("按路径播放")
            }
            Button(
                onClick = actions.startTestTone,
                enabled = !state.playing,
                modifier = Modifier.weight(1f),
            ) {
                Text("测试音")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = actions.startPolarityTest,
            enabled = !state.playing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("声道 / 极性测试")
        }

        Spacer(Modifier.height(12.dp))
        Text(state.status, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(state.metrics, style = MaterialTheme.typography.bodySmall)
    }
}
