// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ImmersivePlayerScreen(state: PlayerState, actions: PlayerActions) {
    MaterialTheme(colorScheme = ImmersiveColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0A0F1E),
                            Color(0xFF151C32),
                            Color(0xFF0A0F1E),
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                UiVersionSelector(
                    selected = state.uiVersion,
                    onSelect = actions.setUiVersion,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { actions.setAboutOpen(true) }) {
                        Text("关于")
                    }
                }
                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF6C8CFF),
                                    Color(0xFFB06CFF),
                                    Color(0xFF6C8CFF),
                                ),
                            ),
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(32.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = Color.White.copy(alpha = 0.9f),
                    )
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    text = state.trackName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = playbackStateText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(28.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(
                        onClick = actions.togglePause,
                        enabled = state.playing,
                        modifier = Modifier.size(76.dp),
                    ) {
                        Icon(
                            imageVector = if (state.paused) {
                                Icons.Rounded.PlayArrow
                            } else {
                                Icons.Rounded.Pause
                            },
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    OutlinedIconButton(
                        onClick = actions.stop,
                        enabled = state.playing,
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    ) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    OutlinedIconButton(
                        onClick = actions.pickAudio,
                        enabled = !state.playing,
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    ) {
                        Icon(
                            Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    OutlinedIconButton(
                        onClick = actions.pickAudioFolder,
                        enabled = !state.playing,
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    ) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = state.metrics,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                )
                PerformanceWarning(
                    state = state,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
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
                    Icon(Icons.Rounded.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (advanced) "收起音效与设置" else "音效与设置")
                }
                if (advanced) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
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
