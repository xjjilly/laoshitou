// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import okhttp3.HttpUrl
import java.util.Locale

enum class WebDavAuthType(val label: String, val description: String) {
    AUTO("自动", "先 Basic；遇到 Digest 挑战时自动切换"),
    BASIC("Basic", "预发送 Basic 认证头，适合 HTTP/HTTPS 测试"),
    DIGEST("Digest", "使用服务器 401 的 Digest challenge 计算响应"),
}

data class WebDavAccount(
    val baseUrl: HttpUrl,
    val username: String,
)

data class WebDavEntry(
    val url: HttpUrl,
    val path: String,
    val name: String,
    val isCollection: Boolean,
    val size: Long? = null,
    val contentType: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
) {
    val extension: String? get() = WebDavMedia.extensionOf(name)
    val isAudio: Boolean
        get() {
            val ext = extension ?: return false
            return !isCollection && ext in WebDavMedia.audioExtensions
        }
    val playbackSupported: Boolean
        get() {
            val ext = extension ?: return true
            return WebDavMedia.supportsPlayback(ext)
        }
}

data class NasCrumb(
    val url: HttpUrl,
    val name: String,
)

object WebDavMedia {
    /** P0 只展示这些格式；DFF 保留在目录中但标记为暂不支持播放。 */
    val audioExtensions: Set<String> = linkedSetOf("flac", "wav", "w64", "dsf", "dff")

    fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.lastIndex) {
            return null
        }
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun mimeForExtension(extension: String): String? = when (extension.lowercase(Locale.ROOT)) {
        "flac" -> "audio/flac"
        "wav", "w64" -> "audio/wav"
        "dsf", "dff" -> "audio/dsd"
        else -> null
    }

    fun supportsPlayback(extension: String): Boolean =
        !extension.equals("dff", ignoreCase = true)

    fun formatSize(bytes: Long?): String {
        if (bytes == null || bytes < 0) {
            return "大小未知"
        }
        if (bytes < 1024) {
            return "$bytes B"
        }
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
