// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Mandatory per-LAN-session role choice. This is intentionally not
 * dismissible: the user must pick sender, receiver, or stay offline.
 */
@Composable
fun RolePromptDialog(
    address: String?,
    onSelected: (DlnaRole) -> Unit,
) {
    MaterialTheme(colorScheme = HifiColors) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("选择本机 DLNA 角色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        address?.let { "已接入局域网：$it" } ?: "已接入局域网",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "DLNA 仅在局域网内通信，不连接互联网。每次接入新的局域网都应重新选择；" +
                            "同一连接只启用一个角色，从根上避免 DMC/DMR 冲突。",
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                    )
                    Button(
                        onClick = { onSelected(DlnaRole.SENDER) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("发送者：共享曲库 / 投送")
                    }
                    Text(
                        DlnaRole.SENDER.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    OutlinedButton(
                        onClick = { onSelected(DlnaRole.RECEIVER) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("接收者：手机作为 DLNA 音箱")
                    }
                    Text(
                        DlnaRole.RECEIVER.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSelected(DlnaRole.OFFLINE) }) {
                    Text("保持离线")
                }
            },
        )
    }
}
