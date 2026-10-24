// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gu.laoshitou.R

@Composable
fun AboutScreen(
    versionName: String,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
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
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) {
                        Text("← 返回")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "关于",
                        style = MaterialTheme.typography.titleMedium,
                        color = HifiCyan,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0E1A16)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(58.dp),
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(
                            "老石头",
                            style = MaterialTheme.typography.headlineMedium,
                            color = HifiAmber,
                        )
                        Text(
                            "老石头 · 版本 $versionName",
                            style = MaterialTheme.typography.bodySmall,
                            color = HifiMuted,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                AboutSection("简介") {
                    Text(
                        "面向音质的本地 Android 播放器：FFmpeg 解码、Oboe 输出、" +
                            "AutoEQ 耳机校准与可调 DSP 链，提供极简、沉浸、高保真三种界面。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                AboutSection("主要功能") {
                    Bullet("本地播放与专辑列表：顺序、循环、随机，支持 FLAC/WAV/MP3/APE/DSD 等。")
                    Bullet("直通 / 处理双模式；同族升频，DSD64（DSF/DFF）本地解码。")
                    Bullet("Oboe 低延迟输出，USB DAC 自动 192k，前台服务与媒体按键。")
                    Bullet("AutoEQ txt / CSV / GraphicEQ 导入，16 段可视化 EQ 与 PK/LSC/HSC/HPF/LPF。")
                    Bullet("2 / 4 阶滤波器、Crossfeed 强度、M/S 声场宽度、动态响度补偿、可调限制器。")
                    Bullet("BRIR 卷积混响（分区 FFT），支持立体声 BRIR WAV 与湿/干比例。")
                }

                AboutSection("技术与第三方") {
                    Text(
                        "FFmpeg 7.1（LGPL-2.1+，静态链接，无 GPL/nonfree 组件，" +
                            "不含 libfdk_aac）、Oboe（Apache-2.0）、" +
                            "libsamplerate（BSD-2-Clause）、libbs2b（MIT）、" +
                            "AutoEQ 公式（MIT）。第三方组件版权归原作者所有。",
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                    )
                }

                AboutSection("许可证") {
                    Text(
                        "本应用以 GNU GPLv3 发布，完整源代码随发布提供。" +
                            "第三方组件许可见 APK 内 assets/licenses/THIRD_PARTY_NOTICES.md。",
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                    )
                }

                AboutSection("隐私与说明") {
                    Text(
                        "本应用未申请 INTERNET 权限，无广告、无在线曲库、无遥测；" +
                            "音频与配置均在本机处理，不上传文件，且已关闭系统云备份。" +
                            "文件访问通过系统选择器或应用私有目录。" +
                            "请使用适度音量，长时间高音量可能损伤听力。",
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                    )
                }

                HorizontalDivider(color = HifiMuted.copy(alpha = 0.25f))
                Spacer(Modifier.height(24.dp))
                Text(
                    "感谢使用。",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiMuted,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(14.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = HifiCyan,
    )
    Spacer(Modifier.height(4.dp))
    content()
}

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = HifiAmber,
        )
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
