// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

class MediaProtocolInfoTest {

    @Test
    fun mapsKnownExtensions() {
        assertEquals("audio/flac", MediaProtocolInfo.forFileName("Track.FLAC")?.mime)
        assertEquals("FLAC", MediaProtocolInfo.forFileName("Track.FLAC")?.dlnaPn)
        assertEquals("audio/mpeg", MediaProtocolInfo.forFileName("a.mp3")?.mime)
        assertEquals("audio/wav", MediaProtocolInfo.forFileName("a.W64")?.mime)
        assertNull(MediaProtocolInfo.forFileName("cover.jpg"))
        assertNull(MediaProtocolInfo.forFileName("no-extension"))
    }

    @Test
    fun mapsMimeToExtension() {
        assertEquals("flac", MediaProtocolInfo.extensionForMime("audio/flac"))
        assertEquals("mp3", MediaProtocolInfo.extensionForMime("audio/mpeg; charset=binary"))
        assertEquals("wav", MediaProtocolInfo.extensionForMime("audio/x-wav"))
        assertNull(MediaProtocolInfo.extensionForMime("application/octet-stream"))
        assertNull(MediaProtocolInfo.extensionForMime(null))
    }

    @Test
    fun appendsSourceExtensionAndPreservesQuery() {
        val uri = URI("http://10.0.0.3:8080/dlna/media/token/42?x=1")
        assertEquals(
            "http://10.0.0.3:8080/dlna/media/token/42.flac?x=1",
            MediaProtocolInfo.ensureMediaSuffix(uri, "Track.flac", "audio/flac").toString(),
        )
    }

    @Test
    fun appendsMimeExtensionWhenFileNameUnknown() {
        val uri = URI("http://10.0.0.3:8080/stream")
        assertEquals(
            "http://10.0.0.3:8080/stream.mp3",
            MediaProtocolInfo.ensureMediaSuffix(uri, null, "audio/mpeg").toString(),
        )
    }

    @Test
    fun doesNotDoubleAppendKnownSuffix() {
        val uri = URI("http://10.0.0.3:8080/a.flac?token=1")
        assertEquals(uri, MediaProtocolInfo.ensureMediaSuffix(uri, "a.mp3", "audio/mpeg"))
    }

    @Test
    fun leavesUnknownUrlsUntouched() {
        val uri = URI("http://10.0.0.3:8080/stream")
        assertEquals(uri, MediaProtocolInfo.ensureMediaSuffix(uri, "cover.jpg", "image/jpeg"))
    }

    @Test
    fun generatesTokenFileName() {
        assertEquals("42.flac", MediaProtocolInfo.mediaFileName("42", "Track.FLAC", null))
        assertEquals("42.mp3", MediaProtocolInfo.mediaFileName("42", null, "audio/mpeg"))
        assertEquals("42", MediaProtocolInfo.mediaFileName("42", null, null))
    }
}
