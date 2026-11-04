// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavUrlTest {
    @Test
    fun parsesBaseUrlWithHostnameAndPort() {
        val url = WebDavUrl.parseBase(" http://10.0.0.10:9005/ ")
        assertEquals("http", url.scheme)
        assertEquals("10.0.0.10", url.host)
        assertEquals(9005, url.port)
    }

    @Test(expected = WebDavUrlException::class)
    fun rejectsNonHttpScheme() {
        WebDavUrl.parseBase("ftp://nas/")
    }

    @Test(expected = WebDavUrlException::class)
    fun rejectsUserInfoInUrl() {
        WebDavUrl.parseBase("http://user:pass@nas/")
    }

    @Test
    fun resolvesPercentEncodedHref() {
        val base = WebDavUrl.parseBase("http://10.0.0.10:9005/musicok/")
        val url = WebDavUrl.resolve(base, "/musicok/%E5%91%A8%E6%9D%B0%E4%BC%A6/Jay/")
        assertEquals("/musicok/%E5%91%A8%E6%9D%B0%E4%BC%A6/Jay/", url.encodedPath)
    }

    @Test
    fun allowsAtSignInPath() {
        val url = WebDavUrl.parseBase("http://10.0.0.10:9005/music@home/")
        assertEquals("10.0.0.10", url.host)
        assertEquals(9005, url.port)
    }

    @Test(expected = WebDavUrlException::class)
    fun rejectsCrossHostHref() {
        val base = WebDavUrl.parseBase("http://10.0.0.10:9005/")
        WebDavUrl.resolve(base, "http://evil.example/x")
    }

    @Test(expected = WebDavUrlException::class)
    fun rejectsCrossPortHref() {
        val base = WebDavUrl.parseBase("http://10.0.0.10:9005/")
        WebDavUrl.resolve(base, "http://10.0.0.10:9006/x")
    }

    @Test(expected = WebDavUrlException::class)
    fun rejectsCrossSchemeHref() {
        val base = WebDavUrl.parseBase("http://10.0.0.10:9005/")
        WebDavUrl.resolve(base, "https://10.0.0.10:9005/x")
    }

    @Test
    fun decodesChineseAndSpaces() {
        val encoded = "/musicok/%E5%91%A8%E6%9D%B0%E4%BC%A6/Jay/" +
            "%E5%A8%98%E5%AD%90%20-%20%E5%91%A8%E6%9D%B0%E4%BC%A6.flac"
        assertEquals(
            "/musicok/周杰伦/Jay/娘子 - 周杰伦.flac",
            WebDavUrl.percentDecodePath(encoded),
        )
    }

    @Test
    fun doesNotTurnPlusIntoSpace() {
        assertEquals("/a+b", WebDavUrl.percentDecodePath("/a+b"))
    }

    @Test
    fun pathKeyIgnoresTrailingSlash() {
        val url = "http://nas/musicok/".toHttpUrl()
        assertEquals("/musicok", WebDavUrl.pathKey(url))
        assertTrue(WebDavUrl.pathKey("http://nas/".toHttpUrl()) == "/")
    }
}
