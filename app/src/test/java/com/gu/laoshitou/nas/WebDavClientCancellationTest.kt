// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebDavClientCancellationTest {
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

    @Test(timeout = 15_000)
    fun cancellingTheCoroutineCancelsTheInFlightCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) {
            runCatching { client.testConnection() }
        }
        val request = withTimeout(5_000) { server.takeRequest(5, TimeUnit.SECONDS) }
        assertTrue("PROPFIND was not sent", request != null)

        val started = System.nanoTime()
        job.cancel()
        withTimeout(3_000) { job.join() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel took ${elapsedMs}ms; the HTTP call was not cancelled", elapsedMs < 2_000)
    }
}
