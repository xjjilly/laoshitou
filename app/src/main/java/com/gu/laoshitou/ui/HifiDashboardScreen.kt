// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

@Composable
fun HifiDashboardScreen(state: PlayerState, actions: PlayerActions) {
    MaterialTheme(colorScheme = HifiColors, typography = HifiTypography) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                UiVersionSelector(
                    selected = state.uiVersion,
                    onSelect = actions.setUiVersion,
                )

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "高保真控制台",
                        style = MaterialTheme.typography.headlineLarge,
                        color = HifiAmber,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { actions.setAboutOpen(true) }) {
                        Text("关于")
                    }
                }
                Text(
                    text = "路线 B · 同族升频 · DSP",
                    style = MaterialTheme.typography.labelSmall,
                    color = HifiCyan,
                )

                HifiCard {
                    Text("当前音轨", style = MaterialTheme.typography.labelSmall, color = HifiCyan)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.trackName,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = playbackStateText(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("源", state.sourceRate, Modifier.weight(1f))
                    MetricCard("目标", state.targetRate, Modifier.weight(1f))
                    MetricCard("输出", state.outputRate, Modifier.weight(1f))
                }
                Text(
                    text = "跨采样率族：${yn(state.crossFamily)} · " +
                        "非整数倍：${yn(state.nonInteger)} · " +
                        "受设备限制：${yn(state.deviceLimited)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiMuted,
                )

                HifiCard {
                    Text("DSP 链", style = MaterialTheme.typography.titleMedium, color = HifiAmber)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DspRow("处理模式", yn(state.processingActive))
                        DspRow("EQ", "${state.eqBands} 段")
                        DspRow("交叉馈送", yn(state.crossfeedActive))
                        DspRow("限制器", yn(state.limiterActive))
                        DspRow("BRIR", yn(state.brirActive))
                        DspRow("独占模式", yn(state.exclusive))
                        DspRow("欠载", "${state.underruns}")
                        DspRow(
                            "采样覆盖",
                            if (state.outputRateOverride > 0) {
                                "${state.outputRateOverride} Hz"
                            } else {
                                "自动"
                            },
                        )
                        DspRow("重采样", resamplerLabel(state.resamplerQuality))
                        DspRow(
                            "CPU",
                            if (state.playing && state.cpuPercent > 0f) {
                                String.format(Locale.US, "%.0f%%", state.cpuPercent)
                            } else {
                                "—"
                            },
                        )
                        DspRow("温度", thermalLabel(state.thermalStatus))
                    }
                }
                PerformanceWarning(state)

                var eqEditorOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { eqEditorOpen = !eqEditorOpen }) {
                    Text(if (eqEditorOpen) "收起 16 段 EQ / 频响曲线" else "16 段 EQ / 频响曲线")
                }
                if (eqEditorOpen) {
                    HifiCard {
                        EqEditorCard(state, actions)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions.pickAudio,
                        enabled = !state.playing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("选择音频")
                    }
                    Button(
                        onClick = actions.togglePause,
                        enabled = state.playing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.paused) "继续" else "暂停")
                    }
                    OutlinedButton(
                        onClick = actions.stop,
                        enabled = state.playing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("停止")
                    }
                }

                HifiCard {
                    MusicLibrary(
                        state = state,
                        actions = actions,
                    )
                }

                var advanced by remember { mutableStateOf(false) }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "收起高级设置" else "高级设置")
                }
                if (advanced) {
                    HifiCard {
                        AdvancedSettingsContent(
                            state = state,
                            actions = actions,
                        )
                    }
                }

                HifiCard(borderColor = HifiCyan.copy(alpha = 0.3f)) {
                    Text("状态", style = MaterialTheme.typography.labelSmall, color = HifiCyan)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiGreen,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.metrics,
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiAmber,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun HifiCard(
    borderColor: androidx.compose.ui.graphics.Color = HifiAmber.copy(alpha = 0.25f),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun MetricCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, HifiAmber.copy(alpha = 0.25f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = HifiCyan,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (value > 0) "$value" else "—",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (value >= 100000) {
                        20.sp
                    } else {
                        MaterialTheme.typography.headlineSmall.fontSize
                    },
                ),
                color = HifiAmber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Hz",
                style = MaterialTheme.typography.labelSmall,
                color = HifiMuted,
            )
        }
    }
}

@Composable
private fun DspRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = HifiMuted,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = HifiCyan,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EqEditorCard(state: PlayerState, actions: PlayerActions) {
    var selected by remember { mutableStateOf(-1) }
    val gains = state.eqEditorGains
    val curve = remember(gains) {
        DoubleArray(kEqEditorCurveSamples) { index ->
            eqCurveValueAt(
                gains,
                index.toDouble() / (kEqEditorCurveSamples - 1).toDouble(),
            )
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "16 段 EQ / 频响曲线",
            style = MaterialTheme.typography.titleMedium,
            color = HifiAmber,
        )
        Text(
            "拖动频点调整增益；实线为 EQ 曲线，虚线含 Preamp；应用后下次播放生效。",
            style = MaterialTheme.typography.bodySmall,
            color = HifiMuted,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = actions.loadCurrentEqIntoEditor) {
            Text("载入当前 EQ 文件")
        }
        Text(
            "把当前 EQ 配置（AutoEQ txt/CSV）拟合到 16 段显示。",
            style = MaterialTheme.typography.bodySmall,
            color = HifiMuted,
        )
        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { position ->
                            val left = 8.dp.toPx()
                            val right = size.width.toFloat() - 8.dp.toPx()
                            selected = nearestBandIndex(position.x, left, right)
                        },
                        onDrag = { change, _ ->
                            val index = selected
                            if (index in EqEditorFrequencies.indices) {
                                val height = size.height.toFloat()
                                val top = height * 0.08f
                                val bottom = height * 0.92f
                                val db = ((top + bottom) * 0.5f - change.position.y) /
                                    ((bottom - top) * 0.5f) * 18f
                                actions.setEqEditorGain(index, db)
                            }
                        },
                        onDragEnd = { actions.saveEqEditor() },
                    )
                },
        ) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = size.height * 0.08f
            val bottom = size.height * 0.92f
            val center = (top + bottom) * 0.5f
            val scale = (bottom - top) * 0.5f / 18f

            fun xAt(frequency: Double): Float {
                val ratio = (log10(frequency / 20.0) / log10(1000.0)).toFloat()
                return left + ratio * (right - left)
            }

            fun yAt(db: Double): Float = center - (db.toFloat() * scale)

            val gridFrequencies = doubleArrayOf(
                20.0, 50.0, 100.0, 200.0, 500.0,
                1000.0, 2000.0, 5000.0, 10000.0, 20000.0,
            )
            for (frequency in gridFrequencies) {
                val x = xAt(frequency)
                drawLine(
                    color = HifiMuted.copy(alpha = 0.22f),
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 1f,
                )
            }
            for (db in intArrayOf(-18, -12, -6, 0, 6, 12, 18)) {
                val y = yAt(db.toDouble())
                drawLine(
                    color = HifiMuted.copy(alpha = 0.22f),
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1f,
                )
            }

            val dryPath = Path()
            val preampPath = Path()
            for (index in curve.indices) {
                val x = left + (right - left) * index.toFloat() / (curve.size - 1).toFloat()
                val y = yAt(curve[index])
                val yPreamp = yAt(curve[index] + state.eqEditorPreamp)
                if (index == 0) {
                    dryPath.moveTo(x, y)
                    preampPath.moveTo(x, yPreamp)
                } else {
                    dryPath.lineTo(x, y)
                    preampPath.lineTo(x, yPreamp)
                }
            }
            drawPath(
                path = dryPath,
                color = HifiAmber,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawPath(
                path = preampPath,
                color = HifiCyan,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                ),
            )

            EqEditorFrequencies.forEachIndexed { index, frequency ->
                val x = xAt(frequency)
                val y = yAt(gains.getOrElse(index) { 0f }.toDouble())
                drawCircle(
                    color = if (index == selected) {
                        HifiCyan
                    } else {
                        Color.White.copy(alpha = 0.9f)
                    },
                    radius = if (index == selected) 7.dp.toPx() else 4.dp.toPx(),
                    center = Offset(x, y),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        if (selected in EqEditorFrequencies.indices) {
            val gain = gains.getOrElse(selected) { 0f }
            Text(
                "${EqEditorFrequencies[selected].toInt()} Hz：" +
                    String.format(Locale.US, "%+.1f", gain) + " dB",
                style = MaterialTheme.typography.bodySmall,
                color = HifiCyan,
            )
        } else {
            Text(
                "点选或拖动曲线上的频点",
                style = MaterialTheme.typography.bodySmall,
                color = HifiMuted,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Preamp：" + String.format(Locale.US, "%+.1f", state.eqEditorPreamp) + " dB",
            style = MaterialTheme.typography.bodySmall,
            color = HifiMuted,
        )
        Slider(
            value = state.eqEditorPreamp.toFloat(),
            onValueChange = { actions.setEqEditorPreamp(it.toDouble()) },
            onValueChangeFinished = { actions.saveEqEditor() },
            valueRange = -24f..12f,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.eqEditorPreamp > 6.0) {
            Text(
                "Preamp > +6 dB：建议使用大耳/高阻耳机，注意音量与失真；" +
                    "Preamp 是削波余量，不是推力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.autoEqEditorPreamp) {
                Text("自动 Preamp")
            }
            OutlinedButton(onClick = actions.resetEqEditor) {
                Text("重置")
            }
            Button(
                onClick = actions.applyEqEditor,
                modifier = Modifier.weight(1f),
            ) {
                Text("应用（下次播放）")
            }
        }
    }
}

private const val kEqEditorCurveSamples = 200

private fun nearestBandIndex(x: Float, left: Float, right: Float): Int {
    var best = 0
    var bestDistance = Float.MAX_VALUE
    EqEditorFrequencies.forEachIndexed { index, frequency ->
        val ratio = (log10(frequency / 20.0) / log10(1000.0)).toFloat()
        val bandX = left + ratio * (right - left)
        val distance = abs(x - bandX)
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

private fun yn(value: Boolean): String = if (value) "是" else "否"

private fun resamplerLabel(quality: Int): String = when (quality) {
    1 -> "BEST"
    2 -> "MEDIUM"
    3 -> "FASTEST"
    else -> "自动"
}

private fun thermalLabel(status: Int): String = when (status) {
    0 -> "正常"
    1 -> "轻"
    2 -> "中"
    3 -> "重"
    4 -> "严重"
    5 -> "紧急"
    6 -> "关机"
    else -> "未知"
}
