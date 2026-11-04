// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gu.laoshitou.nas.NasCrumb
import com.gu.laoshitou.nas.NasRepository
import com.gu.laoshitou.nas.WebDavAccount
import com.gu.laoshitou.nas.WebDavAuthType
import com.gu.laoshitou.nas.WebDavCredentialStore
import com.gu.laoshitou.nas.WebDavEntry
import com.gu.laoshitou.nas.WebDavMedia
import com.gu.laoshitou.nas.WebDavUrl
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

@Composable
fun NasScreen(
    onClose: () -> Unit,
    onCast: (suspend (WebDavEntry) -> String?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WebDavCredentialStore(context) }
    val repository = remember { NasRepository() }

    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf(WebDavAuthType.AUTO) }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf<HttpUrl?>(null) }
    var crumbs by remember { mutableStateOf<List<NasCrumb>>(emptyList()) }
    var entries by remember { mutableStateOf<List<WebDavEntry>>(emptyList()) }

    suspend fun loadDirectory(url: HttpUrl, newCrumbs: List<NasCrumb>) {
        loading = true
        error = null
        try {
            val list = repository.list(url)
            currentUrl = url
            crumbs = newCrumbs
            entries = NasRepository.visible(list)
            status = "当前目录：${list.count { it.isCollection }} 个文件夹 / " +
                "${list.count { it.isAudio }} 首音频"
        } catch (e: Exception) {
            error = e.message ?: "读取目录失败"
        } finally {
            loading = false
        }
    }

    suspend fun performConnect() {
        if (baseUrl.isBlank() || username.isBlank() || password.isEmpty()) {
            error = "请填写服务器地址、用户名和密码"
            return
        }
        val base = try {
            WebDavUrl.parseBase(baseUrl)
        } catch (e: Exception) {
            error = e.message ?: "服务器地址格式不正确"
            return
        }
        val account = WebDavAccount(base, username.trim())
        loading = true
        error = null
        status = "正在连接…"
        try {
            val root = repository.connect(account, password, authType)
            val list = repository.list(base)
            currentUrl = base
            crumbs = listOf(NasCrumb(base, root.name.ifBlank { "NAS" }))
            entries = NasRepository.visible(list)
            connected = true
            store.save(account, password, authType)
            status = "已连接：${list.count { it.isCollection }} 个文件夹 / " +
                "${list.count { it.isAudio }} 首音频"
        } catch (e: Exception) {
            connected = false
            repository.disconnect()
            error = e.message ?: "连接失败"
            status = ""
        } finally {
            loading = false
        }
    }

    fun disconnect() {
        repository.disconnect()
        connected = false
        currentUrl = null
        crumbs = emptyList()
        entries = emptyList()
        status = ""
        error = null
    }

    fun goUp() {
        if (crumbs.size <= 1) {
            onClose()
            return
        }
        val parent = crumbs[crumbs.size - 2]
        scope.launch { loadDirectory(parent.url, crumbs.dropLast(1)) }
    }

    fun openFolder(entry: WebDavEntry) {
        if (!entry.isCollection) {
            return
        }
        scope.launch {
            loadDirectory(entry.url, crumbs + NasCrumb(entry.url, entry.name))
        }
    }

    fun goToCrumb(index: Int) {
        if (index == crumbs.lastIndex || index < 0) {
            return
        }
        val target = crumbs[index]
        scope.launch { loadDirectory(target.url, crumbs.take(index + 1)) }
    }

    fun castEntry(entry: WebDavEntry) {
        val action = onCast ?: return
        scope.launch {
            val message = action(entry)
            if (message == null) {
                error = null
                status = "已投送：${entry.name}"
            } else {
                error = message
            }
        }
    }

    BackHandler {
        if (connected && crumbs.size > 1) {
            goUp()
        } else {
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        val saved = store.load() ?: return@LaunchedEffect
        baseUrl = saved.account.baseUrl.toString()
        username = saved.account.username
        password = saved.password
        authType = saved.authType
        performConnect()
    }

    DisposableEffect(Unit) {
        onDispose { repository.disconnect() }
    }

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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            if (connected && crumbs.size > 1) goUp() else onClose()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        Text("返回")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "NAS 曲库",
                        style = MaterialTheme.typography.titleMedium,
                        color = HifiCyan,
                    )
                    Spacer(Modifier.weight(1f))
                    if (connected) {
                        TextButton(onClick = { disconnect() }) {
                            Text("断开")
                        }
                    }
                }

                if (!connected) {
                    ConnectionCard(
                        baseUrl = baseUrl,
                        username = username,
                        password = password,
                        authType = authType,
                        showPassword = showPassword,
                        loading = loading,
                        error = error,
                        status = status,
                        onBaseUrlChange = { baseUrl = it; error = null },
                        onUsernameChange = { username = it; error = null },
                        onPasswordChange = { password = it; error = null },
                        onAuthTypeChange = { authType = it },
                        onTogglePassword = { showPassword = !showPassword },
                        onConnect = { scope.launch { performConnect() } },
                        onClear = {
                            store.clear()
                            disconnect()
                            baseUrl = ""
                            username = ""
                            password = ""
                            authType = WebDavAuthType.AUTO
                            status = "已清除保存的凭据"
                        },
                    )
                } else {
                    NasCard("连接") {
                        Text(
                            baseUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "用户：$username · 鉴权：${authType.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = HifiMuted,
                        )
                        if (status.isNotBlank()) {
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = HifiGreen,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    currentUrl?.let { url ->
                                        scope.launch { loadDirectory(url, crumbs) }
                                    }
                                },
                                enabled = !loading,
                            ) {
                                Text("刷新")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        crumbs.forEachIndexed { index, crumb ->
                            TextButton(
                                onClick = { goToCrumb(index) },
                                enabled = index != crumbs.lastIndex,
                            ) {
                                Text(
                                    crumb.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (index < crumbs.lastIndex) {
                                Text("/", color = HifiMuted)
                            }
                        }
                    }

                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    NasCard("目录") {
                        if (entries.isEmpty() && !loading) {
                            Text(
                                "此目录没有支持的音频文件或子目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = HifiMuted,
                            )
                        }
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                HorizontalDivider(color = HifiMuted.copy(alpha = 0.15f))
                            }
                            EntryRow(
                                entry = entry,
                                onClick = { openFolder(entry) },
                                onCast = if (onCast != null && !entry.isCollection &&
                                    entry.isAudio && entry.playbackSupported
                                ) {
                                    { castEntry(entry) }
                                } else {
                                    null
                                },
                            )
                        }
                        if (entries.size >= NasRepository.MAX_VISIBLE_ENTRIES) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "仅显示前 ${NasRepository.MAX_VISIBLE_ENTRIES} 项，" +
                                    "请缩小目录范围。",
                                style = MaterialTheme.typography.bodySmall,
                                color = HifiAmber,
                            )
                        }
                    }

                    Text(
                        "P0 只读浏览：FLAC / WAV / DSF / DFF；DFF 暂不支持播放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = HifiMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: WebDavEntry,
    onClick: () -> Unit,
    onCast: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isCollection, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isCollection) Icons.Rounded.Folder else Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = if (entry.isCollection) HifiCyan else HifiAmber,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isCollection) {
                val extension = entry.extension?.uppercase() ?: "AUDIO"
                Text(
                    "$extension · ${WebDavMedia.formatSize(entry.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = HifiMuted,
                )
            }
        }
        if (onCast != null) {
            TextButton(onClick = onCast) {
                Text("投送")
            }
        }
        if (entry.isCollection) {
            Text("›", color = HifiMuted)
        } else if (!entry.playbackSupported) {
            Text(
                "暂不支持播放",
                style = MaterialTheme.typography.labelSmall,
                color = HifiAmber,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    baseUrl: String,
    username: String,
    password: String,
    authType: WebDavAuthType,
    showPassword: Boolean,
    loading: Boolean,
    error: String?,
    status: String,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAuthTypeChange: (WebDavAuthType) -> Unit,
    onTogglePassword: () -> Unit,
    onConnect: () -> Unit,
    onClear: () -> Unit,
) {
    NasCard("连接 WebDAV") {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text("服务器地址") },
            placeholder = { Text("http://NAS-IP:9005/") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = onTogglePassword) {
                    Text(if (showPassword) "隐藏" else "显示")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text("鉴权方式", style = MaterialTheme.typography.bodySmall, color = HifiMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WebDavAuthType.entries.forEach { type ->
                FilterChip(
                    selected = authType == type,
                    onClick = { onAuthTypeChange(type) },
                    label = { Text(type.label) },
                )
            }
        }
        Text(
            authType.description,
            style = MaterialTheme.typography.labelSmall,
            color = HifiMuted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect, enabled = !loading) {
                Text(if (loading) "连接中…" else "连接并测试")
            }
            OutlinedButton(onClick = onClear, enabled = !loading) {
                Text("清除保存")
            }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = HifiGreen,
            )
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "P0 只读浏览：FLAC / WAV / DSF / DFF；DFF 暂不支持播放。",
            style = MaterialTheme.typography.bodySmall,
            color = HifiMuted,
        )
    }
}

@Composable
private fun NasCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = HifiCyan,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
