// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class WebDavUrlException(message: String) : Exception(message)

/**
 * WebDAV URL policy.
 *
 * Unlike the DLNA UrlGuard this intentionally allows hostnames and HTTPS: a NAS
 * is often reached as `nas.local` or through a TLS reverse proxy. It still
 * rejects URL userinfo, fragments, non-HTTP schemes, and cross-origin hrefs
 * (scheme, host or port changes) so credentials never travel to a different
 * origin.
 */
object WebDavUrl {
    fun parseBase(raw: String): HttpUrl {
        val text = raw.trim().trimEnd('/')
        if (text.isEmpty()) {
            throw WebDavUrlException("请输入服务器地址")
        }
        val url = text.toHttpUrlOrNull()
            ?: throw WebDavUrlException("服务器地址格式不正确")
        if (url.scheme != "http" && url.scheme != "https") {
            throw WebDavUrlException("只支持 http:// 或 https://")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw WebDavUrlException("不要把账号密码写在 URL 里")
        }
        if (url.fragment != null) {
            throw WebDavUrlException("服务器地址不能包含 #片段")
        }
        if (url.encodedQuery != null) {
            throw WebDavUrlException("服务器地址不能包含查询参数")
        }
        return url
    }

    fun resolve(base: HttpUrl, href: String): HttpUrl {
        val url = base.resolve(href)
            ?: throw WebDavUrlException("服务器返回了无法解析的 href：$href")
        if (url.scheme != "http" && url.scheme != "https") {
            throw WebDavUrlException("服务器返回了非 HTTP(S) href：$href")
        }
        if (!url.host.equals(base.host, ignoreCase = true) ||
            url.port != base.port ||
            url.scheme != base.scheme
        ) {
            throw WebDavUrlException("服务器返回了跨源 href：${url.scheme}://${url.host}:${url.port}")
        }
        return url
    }

    fun pathKey(url: HttpUrl): String =
        url.encodedPath.trimEnd('/').ifEmpty { "/" }

    fun displayName(url: HttpUrl, explicit: String?): String {
        val fromProp = explicit?.trim()?.takeIf { it.isNotEmpty() }
        if (fromProp != null) {
            return if (fromProp.contains('%')) percentDecodePath(fromProp) else fromProp
        }
        val decoded = percentDecodePath(url.encodedPath)
        return decoded.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
    }

    /**
     * Percent-decodes a URL path without treating `+` as a space. WebDAV hrefs
     * are percent-encoded; filenames may contain `+`, `&`, spaces and Chinese.
     */
    fun percentDecodePath(encoded: String): String {
        if (encoded.indexOf('%') < 0) {
            return encoded
        }
        val out = ByteArrayOutputStream(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val ch = encoded[i]
            if (ch == '%' && i + 2 < encoded.length) {
                val hi = Character.digit(encoded[i + 1], 16)
                val lo = Character.digit(encoded[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            out.write(ch.toString().toByteArray(StandardCharsets.UTF_8))
            i++
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }
}
