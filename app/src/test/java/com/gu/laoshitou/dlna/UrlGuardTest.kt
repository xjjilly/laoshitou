// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import org.junit.Assert.fail
import org.junit.Test

class UrlGuardTest {

    @Test
    fun acceptsPrivateIpv4HttpUrls() {
        UrlGuard.requireAllowed("http://10.0.0.1/file.flac")
        UrlGuard.requireAllowed("http://172.16.0.1:8080/music/track.mp3")
        UrlGuard.requireAllowed("http://10.0.0.1/")
        UrlGuard.requireAllowed("HTTP://10.0.0.1/")
        UrlGuard.requireAllowed("http://10.0.0.1:65535/a?b=c")
    }

    @Test
    fun rejectsNonHttpSchemes() {
        rejects("https://10.0.0.1/a.flac")
        rejects("file:///etc/passwd")
        rejects("ftp://10.0.0.1/a.flac")
        rejects("content://media/external/audio/1")
        rejects("rtsp://10.0.0.1/a")
    }

    @Test
    fun rejectsAddressesOutsideRfc1918() {
        rejects("http://127.0.0.1/")
        rejects("http://0.0.0.0/")
        rejects("http://169.254.1.1/")
        rejects("http://224.0.0.1/")
        rejects("http://8.8.8.8/")
        rejects("http://100.64.0.1/")
        rejects("http://172.32.0.1/")
        rejects("http://192.169.1.1/")
    }

    @Test
    fun rejectsHostnamesAndAmbiguousLiterals() {
        rejects("http://localhost/")
        rejects("http://example.com/")
        rejects("http://10.0.0.1.evil.com/")
        rejects("http://010.0.0.1/")
        rejects("http://0x0a.0.0.1/")
        rejects("http://10.0.0.1./")
        rejects("http://[::1]/")
    }

    @Test
    fun rejectsUserInfoAndFragments() {
        rejects("http://user@10.0.0.1/")
        rejects("http://10.0.0.1@evil.com/")
        rejects("http://10.0.0.1/a#fragment")
    }

    @Test
    fun rejectsInvalidPorts() {
        rejects("http://10.0.0.1:0/")
        rejects("http://10.0.0.1:65536/")
    }

    @Test
    fun rejectsOversizedUrls() {
        rejects("http://10.0.0.1/" + "a".repeat(UrlGuard.MAX_URL_LENGTH))
    }

    @Test
    fun resolvesRelativeRedirectsOnTheSameHost() {
        val current = UrlGuard.requireAllowed("http://10.0.0.1/a/b.flac")
        UrlGuard.validate(UrlGuard.resolveRedirect(current, "/c.flac"))
        UrlGuard.validate(UrlGuard.resolveRedirect(current, "d.flac"))
    }

    @Test
    fun validatesEveryRedirectHop() {
        val current = UrlGuard.requireAllowed("http://10.0.0.1/a.flac")
        UrlGuard.validate(UrlGuard.resolveRedirect(current, "http://10.0.0.2/b.flac"))
        rejectsRedirect(current, "http://8.8.8.8/b.flac")
        rejectsRedirect(current, "//8.8.8.8/b.flac")
        rejectsRedirect(current, "file:///tmp/b.flac")
        rejectsRedirect(current, "")
    }

    private fun rejects(raw: String) {
        try {
            UrlGuard.requireAllowed(raw)
            fail("应当拒绝: $raw")
        } catch (_: UrlGuardException) {
            // expected
        }
    }

    private fun rejectsRedirect(current: java.net.URI, location: String) {
        try {
            UrlGuard.resolveRedirect(current, location)
            fail("应当拒绝重定向: $location")
        } catch (_: UrlGuardException) {
            // expected
        }
    }
}
