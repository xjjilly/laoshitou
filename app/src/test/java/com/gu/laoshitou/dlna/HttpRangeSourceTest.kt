// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

class HttpRangeSourceTest {

    private lateinit var server: TestHttpServer
    private lateinit var data: ByteArray
    private lateinit var fileUri: URI
    private lateinit var redirectUri: URI
    private lateinit var noSizeUri: URI

    // Loopback is rejected by the production policy; this test-only policy
    // lets us exercise the HTTP/Range mechanics against a local server.
    private val testPolicy = UrlPolicy(
        requireAllowed = { URI(it) },
        resolveRedirect = { current, location -> current.resolve(location) },
    )

    @Before
    fun setUp() {
        data = ByteArray(200_000) { (it % 251).toByte() }
        server = TestHttpServer(data)
        val port = server.port
        fileUri = URI("http://127.0.0.1:$port/file")
        redirectUri = URI("http://127.0.0.1:$port/redirect")
        noSizeUri = URI("http://127.0.0.1:$port/nosize")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun readsSizeAndContents() {
        HttpRangeSource(fileUri.toString(), testPolicy).use { source ->
            assertEquals(data.size.toLong(), source.getSize())

            val buffer = ByteArray(4096)
            var position = 0
            while (true) {
                val n = source.readAt(position.toLong(), buffer, 0, buffer.size)
                if (n < 0) {
                    break
                }
                assertArrayEquals(
                    data.copyOfRange(position, position + n),
                    buffer.copyOf(n),
                )
                position += n
            }
            assertEquals(data.size, position)
        }
    }

    @Test
    fun seeksForwardAndBackward() {
        HttpRangeSource(fileUri.toString(), testPolicy).use { source ->
            val buffer = ByteArray(16)
            assertEquals(16, source.readAt(100_000, buffer, 0, buffer.size))
            assertArrayEquals(data.copyOfRange(100_000, 100_016), buffer)

            assertEquals(16, source.readAt(0, buffer, 0, buffer.size))
            assertArrayEquals(data.copyOfRange(0, 16), buffer)

            assertEquals(10, source.readAt((data.size - 10).toLong(), buffer, 0, buffer.size))
            assertArrayEquals(data.copyOfRange(data.size - 10, data.size), buffer.copyOf(10))

            assertEquals(-1, source.readAt(data.size.toLong(), buffer, 0, buffer.size))
        }
    }

    @Test
    fun followsValidatedRedirects() {
        HttpRangeSource(redirectUri.toString(), testPolicy).use { source ->
            assertEquals(data.size.toLong(), source.getSize())
            val buffer = ByteArray(8)
            assertEquals(8, source.readAt(123, buffer, 0, buffer.size))
            assertArrayEquals(data.copyOfRange(123, 131), buffer)
        }
    }

    @Test
    fun defaultPolicyRejectsLoopback() {
        assertThrows(UrlGuardException::class.java) {
            HttpRangeSource(fileUri.toString())
        }
    }

    @Test
    fun rejectsUnknownSize() {
        assertThrows(IOException::class.java) {
            HttpRangeSource(noSizeUri.toString(), testPolicy).use { it.getSize() }
        }
    }
}

/**
 * Minimal blocking HTTP server for unit tests. It intentionally supports only
 * the request shapes used by [HttpRangeSource]: HEAD, GET, Range and one
 * relative redirect.
 */
private class TestHttpServer(private val data: ByteArray) : Closeable {
    private val serverSocket = ServerSocket(
        0,
        50,
        InetAddress.getByName("127.0.0.1"),
    )
    private val executor = Executors.newCachedThreadPool()
    private val acceptThread = Thread(::acceptLoop, "test-http-accept").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = serverSocket.localPort

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            executor.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                return
            }
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) {
                    break
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                }
            }

            val out = client.getOutputStream()
            when (path) {
                "/redirect" -> respond(
                    out,
                    status = "302 Found",
                    headers = mapOf("Location" to "/file", "Content-Length" to "0"),
                    body = ByteArray(0),
                    headOnly = method == "HEAD",
                )
                "/nosize" -> {
                    val body = byteArrayOf(1, 2, 3)
                    respond(
                        out,
                        status = "200 OK",
                        headers = emptyMap(),
                        body = body,
                        headOnly = method == "HEAD",
                    )
                }
                else -> serveFile(out, method, headers["range"])
            }
        }
    }

    private fun serveFile(out: java.io.OutputStream, method: String, rangeHeader: String?) {
        val range = parseRange(rangeHeader, data.size)
        if (method == "HEAD") {
            respond(
                out,
                status = "200 OK",
                headers = mapOf(
                    "Content-Length" to data.size.toString(),
                    "Accept-Ranges" to "bytes",
                ),
                body = ByteArray(0),
                headOnly = true,
            )
            return
        }
        if (range == null) {
            respond(
                out,
                status = "200 OK",
                headers = mapOf(
                    "Content-Length" to data.size.toString(),
                    "Accept-Ranges" to "bytes",
                ),
                body = data,
                headOnly = false,
            )
        } else {
            val (start, end) = range
            val body = data.copyOfRange(start, end + 1)
            respond(
                out,
                status = "206 Partial Content",
                headers = mapOf(
                    "Content-Range" to "bytes $start-$end/${data.size}",
                    "Content-Length" to body.size.toString(),
                    "Accept-Ranges" to "bytes",
                ),
                body = body,
                headOnly = false,
            )
        }
    }

    private fun respond(
        out: java.io.OutputStream,
        status: String,
        headers: Map<String, String>,
        body: ByteArray,
        headOnly: Boolean,
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            for ((name, value) in headers) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) {
            out.write(body)
        }
        out.flush()
    }

    private fun parseRange(header: String?, size: Int): Pair<Int, Int>? {
        if (header == null || !header.startsWith("bytes=")) {
            return null
        }
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash <= 0) {
            return null
        }
        val start = spec.substring(0, dash).toIntOrNull() ?: return null
        val end = spec.substring(dash + 1).toIntOrNull() ?: (size - 1)
        if (start < 0 || start >= size || end < start) {
            return null
        }
        return start to minOf(end, size - 1)
    }

    override fun close() {
        serverSocket.close()
        executor.shutdownNow()
        acceptThread.interrupt()
    }
}
