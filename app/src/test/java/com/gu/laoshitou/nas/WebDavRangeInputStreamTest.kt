// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WebDavRangeInputStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val account = WebDavAccount(WebDavUrl.parseBase(server.url("/").toString()), "user")
        client = WebDavClient(account, "pass", WebDavAuthType.BASIC)
    }

    @After
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    @Test
    fun skipReopensWithTheNewRangeInsteadOfDownloadingFromZero() {
        val file = ByteArray(64) { it.toByte() }
        server.enqueue(rangeResponse(file, start = 10))
        val stream = WebDavRangeInputStream(client, server.url("/track.flac"), 0, 63)

        assertEquals(10L, stream.skip(10))
        assertEquals(file[10].toInt() and 0xff, stream.read())

        val first = server.takeRequest()
        assertEquals("bytes=10-63", first.getHeader("Range"))
        assertEquals(1, server.requestCount)

        server.enqueue(rangeResponse(file, start = 15))
        assertEquals(4L, stream.skip(4))
        assertEquals(file[15].toInt() and 0xff, stream.read())

        val second = server.takeRequest()
        assertEquals("bytes=15-63", second.getHeader("Range"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun serverIgnoringRangeIsHandledByDiscarding() {
        val file = ByteArray(32) { (100 + it).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(file)),
        )
        val stream = WebDavRangeInputStream(client, server.url("/track.flac"), 0, 31)

        assertEquals(5L, stream.skip(5))
        assertEquals(file[5].toInt() and 0xff, stream.read())
        assertEquals("bytes=5-31", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun unknownLengthReopensWithAnOpenEndedRange() {
        val file = ByteArray(16) { it.toByte() }
        server.enqueue(rangeResponse(file, start = 8, total = 16))
        val stream = WebDavRangeInputStream(client, server.url("/track.flac"), 0, Long.MAX_VALUE)

        assertEquals(8L, stream.skip(8))
        assertEquals(file[8].toInt() and 0xff, stream.read())
        assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun skipPastTheEndDoesNotOpenAConnection() {
        val stream = WebDavRangeInputStream(client, server.url("/track.flac"), 0, 63)

        assertEquals(64L, stream.skip(100))
        assertEquals(-1, stream.read())
        assertEquals(0, server.requestCount)
    }

    private fun rangeResponse(file: ByteArray, start: Int, total: Int = file.size): MockResponse {
        val body = Buffer().write(file, start, file.size - start)
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-${total - 1}/$total")
            .setBody(body)
    }
}
