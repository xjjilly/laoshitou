// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Digest authenticator for the WebDAV client.
 *
 * OkHttp has no built-in HTTP Digest support. This implements the common
 * MD5 / SHA-256 + qop=auth challenge used by NAS WebDAV servers. Basic auth is
 * added preemptively by [WebDavClient] when the selected mode allows it.
 */
internal class WebDavAuthenticator(
    private val username: String,
    private val password: String,
    private val mode: WebDavAuthType,
) : Authenticator {
    private val random = SecureRandom()
    private val nonceCounts = ConcurrentHashMap<String, Int>()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (mode == WebDavAuthType.BASIC || response.code != 401) {
            return null
        }
        val challenge = response.headers("WWW-Authenticate")
            .firstOrNull { it.startsWith("Digest ", ignoreCase = true) }
            ?: return null
        val params = parseChallenge(challenge.substringAfter(' ').trim())
        val stale = params["stale"]?.equals("true", ignoreCase = true) == true
        val alreadyDigest = response.request.header("Authorization")
            ?.startsWith("Digest ", ignoreCase = true) == true
        if (alreadyDigest && !stale) {
            return null
        }

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]
            ?.split(',')
            ?.map { it.trim() }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }
        if (params["qop"] != null && qop == null) {
            // auth-int requires hashing the request body; not supported in P0.
            return null
        }
        val algorithm = params["algorithm"] ?: "MD5"
        val digestName = if (algorithm.contains("SHA-256", ignoreCase = true)) "SHA-256" else "MD5"
        val cnonce = randomHex(16)
        val ncValue = nonceCounts.merge(nonce, 1, Int::plus) ?: 1
        val nc = String.format(Locale.US, "%08x", ncValue)
        val uri = response.request.url.encodedPath +
            (response.request.url.encodedQuery?.let { "?$it" } ?: "")
        val baseHa1 = hash("$username:$realm:$password", digestName)
        val ha1 = if (algorithm.contains("sess", ignoreCase = true)) {
            hash("$baseHa1:$nonce:$cnonce", digestName)
        } else {
            baseHa1
        }
        val ha2 = hash("${response.request.method}:$uri", digestName)
        val digestResponse = if (qop != null) {
            hash("$ha1:$nonce:$nc:$cnonce:${qop.lowercase(Locale.ROOT)}:$ha2", digestName)
        } else {
            hash("$ha1:$nonce:$ha2", digestName)
        }
        val header = buildString {
            append("Digest username=\"").append(escape(username)).append("\", ")
            append("realm=\"").append(escape(realm)).append("\", ")
            append("nonce=\"").append(escape(nonce)).append("\", ")
            append("uri=\"").append(escape(uri)).append("\"")
            if (qop != null) {
                append(", cnonce=\"").append(cnonce).append("\"")
                append(", nc=").append(nc)
            }
            append(", response=\"").append(digestResponse).append("\"")
            if (qop != null) {
                // dufs (and curl) send the qop value quoted; some strict parsers
                // reject the RFC token form here.
                append(", qop=\"").append(qop).append("\"")
            }
            params["algorithm"]?.let { append(", algorithm=").append(it) }
            params["opaque"]?.let { append(", opaque=\"").append(escape(it)).append("\"") }
        }
        return response.request.newBuilder()
            .header("Authorization", header)
            .build()
    }

    private fun parseChallenge(input: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var index = 0
        while (index < input.length) {
            while (index < input.length && (input[index] == ',' || input[index].isWhitespace())) {
                index++
            }
            val keyStart = index
            while (index < input.length && input[index] != '=') {
                index++
            }
            if (index >= input.length) {
                break
            }
            val key = input.substring(keyStart, index).trim().lowercase(Locale.ROOT)
            index++
            while (index < input.length && input[index].isWhitespace()) {
                index++
            }
            if (index < input.length && input[index] == '"') {
                index++
                val value = StringBuilder()
                while (index < input.length && input[index] != '"') {
                    if (input[index] == '\\' && index + 1 < input.length) {
                        index++
                    }
                    value.append(input[index])
                    index++
                }
                if (index < input.length) {
                    index++
                }
                result[key] = value.toString()
            } else {
                val valueStart = index
                while (index < input.length && input[index] != ',') {
                    index++
                }
                result[key] = input.substring(valueStart, index).trim()
            }
        }
        return result
    }

    private fun hash(value: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val valueByte = byte.toInt() and 0xff
            out.append(HEX[valueByte ushr 4]).append(HEX[valueByte and 0x0f])
        }
        return out.toString()
    }

    private fun randomHex(length: Int): String {
        val bytes = ByteArray((length + 1) / 2)
        random.nextBytes(bytes)
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val valueByte = byte.toInt() and 0xff
            out.append(HEX[valueByte ushr 4]).append(HEX[valueByte and 0x0f])
        }
        return out.toString().take(length)
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
