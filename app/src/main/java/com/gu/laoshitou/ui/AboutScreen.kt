// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gu.laoshitou.R

// 闲鱼 · 老石头 PC 完整版（¥299）
private const val PC_STORE_URL =
    "https://www.goofish.com/item?id=1100077192906"

private const val PC_SETUP_URL = "https://wwbdj.lanzoul.com/it8004fburyh"
private const val PC_SETUP_PASSWORD = "15u8"
private const val PC_PORTABLE_URL = "https://wwbdj.lanzoul.com/iAMFE4fburtc"
private const val PC_PORTABLE_PASSWORD = "gavy"

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
                        "默认离线：未开启 DLNA 时不发起任何网络请求。" +
                            "DLNA 为可选功能，开启后只访问局域网，不连接互联网、不上传文件；" +
                            "为此申请 INTERNET、Wi-Fi 状态与附近 Wi-Fi 权限。无广告、无在线曲库、无遥测；" +
                            "音频与配置均在本机处理，且已关闭系统云备份。" +
                            "音乐库读取手机根目录的 laoshitou/ 文件夹，需「所有文件访问」权限；" +
                            "其余文件访问通过系统选择器。" +
                            "请使用适度音量，长时间高音量可能损伤听力。",
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                    )
                }

                Spacer(Modifier.height(22.dp))
                PcEcosystemCard()
                Spacer(Modifier.height(22.dp))

                HorizontalDivider(color = HifiMuted.copy(alpha = 0.25f))
                Spacer(Modifier.height(20.dp))
                Text(
                    "支持开发者",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = HifiAmber,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "本软件免费开源。如果它帮到了你，可以自愿请作者喝杯咖啡；" +
                        "不打赏也完全不影响任何功能。",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiMuted,
                )
                Spacer(Modifier.height(10.dp))
                Image(
                    painter = painterResource(R.drawable.reward_qr),
                    contentDescription = "微信赞赏码",
                    modifier = Modifier
                        .size(220.dp)
                        .align(Alignment.CenterHorizontally),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = HifiMuted.copy(alpha = 0.15f))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.free_software_notice),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = HifiMuted.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "联系作者：gsl_snake",
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

/**
 * Cross-promotion card for the desktop edition. Kept deliberately restrained:
 * a thin gold hairline, near-black panel and monospace micro-labels so it reads
 * as part of the player's own "ecosystem" rather than a banner ad.
 */
@Composable
private fun PcEcosystemCard() {
    val context = LocalContext.current
    var showDownloads by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val goldLine = Brush.linearGradient(
        colors = listOf(
            HifiAmber.copy(alpha = 0.18f),
            HifiAmber.copy(alpha = 0.82f),
            HifiAmber.copy(alpha = 0.22f),
            HifiAmber.copy(alpha = 0.72f),
            HifiAmber.copy(alpha = 0.18f),
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF17171A), Color(0xFF0B0B0D)),
                ),
            )
            .border(1.dp, goldLine, shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            HifiAmber.copy(alpha = 0.07f),
                            Color.Transparent,
                        ),
                        center = Offset(0f, 0f),
                        radius = 560f,
                    ),
                ),
        )
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LAOSHITOU // ECOSYSTEM",
                    color = HifiAmber.copy(alpha = 0.55f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            HifiAmber.copy(alpha = 0.45f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "PC",
                        color = HifiAmber.copy(alpha = 0.8f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "老石头 PC 端 (v1.0.0) 现已发布",
                style = MaterialTheme.typography.titleMedium.copy(
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFFC79A3E),
                            Color(0xFFFFF0BF),
                            Color(0xFFD9AE55),
                        ),
                    ),
                    fontWeight = FontWeight.SemiBold,
                ),
                fontSize = 16.sp,
                lineHeight = 23.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "WASAPI 独占 / NAS 断网续传 / 专属高定皮肤",
                color = HifiMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { showDownloads = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HifiAmber,
                        contentColor = Color(0xFF1A1408),
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "下载 PC 版",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.size(10.dp))
                OutlinedButton(
                    onClick = { openUrl(context, PC_STORE_URL) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, HifiAmber.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HifiAmber,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "[ 了解详情 ]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "WIN 10/11 · X64",
                    color = HifiMuted.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
        }
    }

    if (showDownloads) {
        PcDownloadDialog(onDismiss = { showDownloads = false })
    }
}

@Composable
private fun PcDownloadDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161A),
        title = {
            Column {
                Text(
                    text = "老石头 PC 端下载",
                    color = HifiAmber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = "蓝奏云 · 点击卡片用浏览器打开",
                    color = HifiMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PcDownloadEntry(
                    name = "LaoShiTou-PC-v1.0-Setup.exe",
                    description = "安装版 · 推荐",
                    url = PC_SETUP_URL,
                    password = PC_SETUP_PASSWORD,
                    onOpen = { openUrl(context, PC_SETUP_URL) },
                    onCopyPassword = {
                        clipboard.setText(AnnotatedString(PC_SETUP_PASSWORD))
                        Toast.makeText(context, "密码已复制", Toast.LENGTH_SHORT).show()
                    },
                )
                PcDownloadEntry(
                    name = "LaoShiTou-PC-v1.0-portable.zip",
                    description = "便携版 · 解压即用",
                    url = PC_PORTABLE_URL,
                    password = PC_PORTABLE_PASSWORD,
                    onOpen = { openUrl(context, PC_PORTABLE_URL) },
                    onCopyPassword = {
                        clipboard.setText(AnnotatedString(PC_PORTABLE_PASSWORD))
                        Toast.makeText(context, "密码已复制", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = HifiAmber)
            }
        },
    )
}

@Composable
private fun PcDownloadEntry(
    name: String,
    description: String,
    url: String,
    password: String,
    onOpen: () -> Unit,
    onCopyPassword: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF1C1C20))
            .border(1.dp, HifiAmber.copy(alpha = 0.28f), shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = name,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = description,
            color = HifiMuted,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = url,
            color = HifiAmber.copy(alpha = 0.65f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "密码",
                color = HifiMuted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = password,
                color = HifiAmber,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onCopyPassword,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("复制", color = HifiAmber, fontSize = 11.sp)
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
}
