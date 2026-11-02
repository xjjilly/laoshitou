// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavAuthTest {
    @Test
    fun buildsDigestHeaderFromDufsChallenge() {
        val request = Request.Builder()
            .url("http://192.168.1.10:9006/")
            .method(
                "PROPFIND",
                """<?xml version="1.0"?><D:propfind xmlns:D="DAV:"/>"""
                    .toRequestBody("application/xml".toMediaType()),
            )
            .build()
        val challenge = "Digest realm=\"DUFS\", nonce=\"abc123\", qop=\"auth\""
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .header("WWW-Authenticate", challenge)
            .body("".toResponseBody())
            .build()

        val next = WebDavAuthenticator("laoshitou", "secret", WebDavAuthType.DIGEST)
            .authenticate(null, response)
        val header = requireNotNull(next?.header("Authorization")) {
            "auth header missing; challenge=$challenge"
        }
        assertTrue("unexpected header: $header", header.startsWith("Digest "))
        assertTrue("missing username: $header", header.contains("username=\"laoshitou\""))
        assertTrue("missing realm: $header", header.contains("realm=\"DUFS\""))
        assertTrue("missing qop: $header", header.contains("qop=\"auth\""))
        assertTrue("missing nc: $header", header.contains("nc=00000001"))
        assertTrue("missing cnonce: $header", header.contains("cnonce=\""))
        assertTrue("missing response: $header", header.contains("response=\""))
        assertFalse(
            "algorithm should be omitted for default MD5: $header",
            header.contains("algorithm="),
        )
    }

    @Test
    fun basicModeDoesNotBuildDigestHeader() {
        val request = Request.Builder()
            .url("http://192.168.1.10:9005/")
            .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .header("WWW-Authenticate", "Digest realm=\"DUFS\", nonce=\"abc\", qop=\"auth\"")
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()
        val next = WebDavAuthenticator("user", "pass", WebDavAuthType.BASIC)
            .authenticate(null, response)
        assertTrue(next == null)
    }
}
