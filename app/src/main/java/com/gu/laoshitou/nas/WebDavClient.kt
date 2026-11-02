// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal WebDAV client for P0: connect/test and lazy PROPFIND listing.
 *
 * The client is deliberately narrow. It does not write, upload, delete or
 * follow cross-host redirects; credentials are only sent to the configured
 * origin. Call [close] when the session ends.
 */
class WebDavClient(
    private val account: WebDavAccount,
    private val password: String,
    private val authType: WebDavAuthType,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(WebDavAuthenticator(account.username, password, authType))
        .build()

    suspend fun testConnection(): WebDavEntry = withContext(Dispatchers.IO) {
        propfind(account.baseUrl, depth = 0).firstOrNull()
            ?: throw WebDavException("服务器未返回根目录信息")
    }

    suspend fun list(url: HttpUrl): List<WebDavEntry> = withContext(Dispatchers.IO) {
        propfind(url, depth = 1)
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /**
     * Opens a GET response for the given range. The caller owns the returned
     * response and must close it. This is blocking and is meant for the DMS
     * media endpoint thread, not for coroutines.
     */
    internal fun openRange(url: HttpUrl, start: Long, endInclusive: Long?): Response {
        val range = if (endInclusive == null) {
            "bytes=$start-"
        } else {
            "bytes=$start-$endInclusive"
        }
        val request = buildGetRequest(url).newBuilder().header("Range", range).build()
        return try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw mapNetworkError(e)
        }
    }

    private fun buildGetRequest(url: HttpUrl): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "*/*")
            .header("User-Agent", USER_AGENT)
        if (authType != WebDavAuthType.DIGEST) {
            builder.header("Authorization", basicHeader(account.username, password))
        }
        return builder.build()
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    private suspend fun propfind(start: HttpUrl, depth: Int): List<WebDavEntry> {
        var current = start
        var redirects = 0
        while (true) {
            val response = try {
                execute(buildRequest(current, depth))
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw mapNetworkError(e)
            }
            var next: HttpUrl? = null
            try {
                when (response.code) {
                    in 300..399 -> {
                        val location = response.header("Location")
                            ?: throw WebDavException(
                                "服务器返回重定向但缺少 Location" +
                                    "（HTTP ${response.code}）",
                            )
                        if (redirects >= MAX_REDIRECTS) {
                            throw WebDavException("重定向次数过多")
                        }
                        next = WebDavUrl.resolve(current, location)
                    }
                    401 -> throw WebDavException(
                        "认证失败（401）：请检查用户名、密码和鉴权方式",
                    )
                    403 -> throw WebDavException("没有访问权限（403）")
                    404 -> throw WebDavException(
                        "路径不存在（404）：${current.encodedPath}",
                    )
                    405 -> throw WebDavException("服务器不支持 PROPFIND（405）")
                    207, 200 -> {
                        val body = try {
                            response.body?.string().orEmpty()
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw mapNetworkError(e)
                        }
                        if (body.isBlank()) {
                            throw WebDavException("服务器返回空响应")
                        }
                        val parsed = try {
                            WebDavXml.parseMultiStatus(body)
                        } catch (e: Exception) {
                            throw WebDavException("解析 PROPFIND 响应失败：${e.message}", e)
                        }
                        val entries = toEntries(parsed, current, depth)
                        if (depth == 0 && entries.isEmpty()) {
                            return listOf(rootEntry(current))
                        }
                        return entries
                    }
                    else -> {
                        val snippet = try {
                            response.body?.string()?.take(200)?.replace(Regex("\\s+"), " ")
                        } catch (_: IOException) {
                            null
                        }.orEmpty()
                        throw WebDavException(
                            "服务器返回 HTTP ${response.code}" +
                                if (snippet.isBlank()) "" else "：$snippet",
                        )
                    }
                }
            } finally {
                response.close()
            }
            // Every non-redirect branch above returns or throws.
            current = requireNotNull(next) { "重定向目标缺失" }
            redirects++
        }
    }

    private fun buildRequest(url: HttpUrl, depth: Int): Request {
        val body = PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", depth.toString())
            .header("Accept", "application/xml, text/xml")
            .header("User-Agent", USER_AGENT)
        if (authType != WebDavAuthType.DIGEST) {
            builder.header("Authorization", basicHeader(account.username, password))
        }
        return builder.build()
    }

    private fun toEntries(
        parsed: List<WebDavXmlEntry>,
        base: HttpUrl,
        depth: Int,
    ): List<WebDavEntry> {
        val baseKey = WebDavUrl.pathKey(base)
        return parsed.mapNotNull { raw ->
            val href = raw.href.trim()
            if (href.isEmpty()) {
                return@mapNotNull null
            }
            val url = try {
                WebDavUrl.resolve(base, href)
            } catch (e: WebDavUrlException) {
                throw WebDavException("服务器返回了跨主机/非法 href：$href", e)
            }
            if (depth == 1 && WebDavUrl.pathKey(url) == baseKey) {
                return@mapNotNull null
            }
            val path = WebDavUrl.percentDecodePath(url.encodedPath)
            val name = WebDavUrl.displayName(url, raw.displayName).let {
                if (WebDavUrl.pathKey(url) == "/" && it == "/") base.host else it
            }
            WebDavEntry(
                url = url,
                path = path,
                name = name,
                isCollection = raw.isCollection,
                size = raw.size,
                contentType = raw.contentType,
                etag = raw.etag,
                lastModified = raw.lastModified,
            )
        }
    }

    private fun rootEntry(url: HttpUrl): WebDavEntry = WebDavEntry(
        url = url,
        path = WebDavUrl.percentDecodePath(url.encodedPath),
        name = url.host.ifBlank { "NAS" },
        isCollection = true,
    )

    private fun basicHeader(username: String, password: String): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    private fun mapNetworkError(e: IOException): WebDavException = when (e) {
        is UnknownHostException -> WebDavException("找不到服务器：${e.message}", e)
        is ConnectException -> WebDavException("无法连接服务器：${e.message}", e)
        is SocketTimeoutException -> WebDavException("连接超时，请检查网络或端口", e)
        else -> WebDavException("网络错误：${e.message}", e)
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val USER_AGENT = "LaoShiTou-NAS/0.1"
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:resourcetype/>
    <D:getcontentlength/>
    <D:getcontenttype/>
    <D:getetag/>
    <D:getlastmodified/>
  </D:prop>
</D:propfind>"""
    }
}
