// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MinimalPlayerScreen(state: PlayerState, actions: PlayerActions) {
    MaterialTheme(colorScheme = MinimalColors, typography = MinimalTypography) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                UiVersionSelector(
                    selected = state.uiVersion,
                    onSelect = actions.setUiVersion,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "老石头",
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { actions.setAboutOpen(true) }) {
                        Text("关于")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "高保真 · 同族升频 · DSP",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "当前音轨",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.trackName,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = playbackStateText(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.height(18.dp))

                        Button(
                            onClick = actions.pickAudio,
                            enabled = !state.playing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Icon(Icons.Rounded.LibraryMusic, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("选择音频文件")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = actions.pickAudioFolder,
                            enabled = !state.playing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("选择音频文件夹")
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(
                                onClick = actions.togglePause,
                                enabled = state.playing,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = if (state.paused) {
                                        Icons.Rounded.PlayArrow
                                    } else {
                                        Icons.Rounded.Pause
                                    },
                                    contentDescription = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.paused) "继续" else "暂停")
                            }
                            OutlinedButton(
                                onClick = actions.stop,
                                enabled = state.playing,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("停止")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.metrics,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PerformanceWarning(
                            state = state,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    MusicLibrary(
                        state = state,
                        actions = actions,
                        modifier = Modifier.padding(20.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                var advanced by remember { mutableStateOf(false) }
                TextButton(onClick = { advanced = !advanced }) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (advanced) "收起音效与设置" else "音效与设置")
                }
                if (advanced) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        QuickSettingsContent(
                            state = state,
                            actions = actions,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
