// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug-only HTTP server that serves a generated WAV file with Range/206.
 * It is used by the M0 activity to validate [HttpRangeSource] and
 * MediaPlayer on a real device. It is not part of the release APK.
 */
internal class DebugRangeServer(
    private val bindAddress: Inet4Address,
    private val body: ByteArray,
) : Closeable {

    private val serverSocket = ServerSocket(0, 32, bindAddress)
    private val executor = Executors.newCachedThreadPool()
    private val acceptThread = Thread(::acceptLoop, "m0-range-accept").apply {
        isDaemon = true
        start()
    }

    fun start(): String {
        val url = "http://${bindAddress.hostAddress}:${serverSocket.localPort}/tone.wav"
        Log.i(TAG, "Range 服务已启动: $url (${body.size} bytes)")
        return url
    }

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
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            Log.i(TAG, "Range 请求: $method $path range=${headers["range"] ?: "-"}")
            val out = client.getOutputStream()
            if (path != "/tone.wav") {
                writeResponse(out, "404 Not Found", emptyMap(), null, headOnly = method == "HEAD")
                return
            }
            if (method != "GET" && method != "HEAD") {
                writeResponse(out, "405 Method Not Allowed", emptyMap(), null, headOnly = false)
                return
            }

            val range = parseRange(headers["range"])
            if (range == null) {
                writeResponse(
                    out,
                    "200 OK",
                    mapOf(
                        "Content-Type" to "audio/wav",
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to body.size.toString(),
                    ),
                    body,
                    headOnly = method == "HEAD",
                )
            } else {
                val (start, endInclusive) = range
                if (start >= body.size) {
                    writeResponse(
                        out,
                        "416 Range Not Satisfiable",
                        mapOf("Content-Range" to "bytes */${body.size}"),
                        null,
                        headOnly = method == "HEAD",
                    )
                    return
                }
                val end = minOf(endInclusive, body.size - 1)
                val length = end - start + 1
                val slice = body.copyOfRange(start, end + 1)
                writeResponse(
                    out,
                    "206 Partial Content",
                    mapOf(
                        "Content-Type" to "audio/wav",
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes $start-$end/${body.size}",
                        "Content-Length" to length.toString(),
                    ),
                    slice,
                    headOnly = method == "HEAD",
                )
            }
        }
    }

    private fun writeResponse(
        out: OutputStream,
        status: String,
        headers: Map<String, String>,
        payload: ByteArray?,
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
        if (!headOnly && payload != null) {
            out.write(payload)
        }
        out.flush()
    }

    private fun parseRange(header: String?): Pair<Int, Int>? {
        if (header == null || !header.startsWith("bytes=")) {
            return null
        }
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash <= 0) {
            return null
        }
        val start = spec.substring(0, dash).toIntOrNull() ?: return null
        val end = spec.substring(dash + 1).toIntOrNull() ?: (body.size - 1)
        if (start < 0 || end < start) {
            return null
        }
        return start to end
    }

    override fun close() {
        serverSocket.close()
        executor.shutdownNow()
        acceptThread.interrupt()
        Log.i(TAG, "Range 服务已停止")
    }

    companion object {
        private const val TAG = "M0Dlna"

        /** A quiet 10-second 440 Hz stereo 16-bit WAV. */
        fun testToneWav(seconds: Int = 10, sampleRate: Int = 44_100): ByteArray {
            val frameCount = seconds * sampleRate
            val dataSize = frameCount * 4
            val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(36 + dataSize)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16)
            buffer.putShort(1.toShort())
            buffer.putShort(2.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(sampleRate * 4)
            buffer.putShort(4.toShort())
            buffer.putShort(16.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(dataSize)
            val amplitude = (Short.MAX_VALUE * 0.12).toInt()
            for (i in 0 until frameCount) {
                val sample = (sin(2.0 * PI * 440.0 * i / sampleRate) * amplitude).toInt()
                buffer.putShort(sample.toShort())
                buffer.putShort(sample.toShort())
            }
            return buffer.array()
        }

        fun formatSize(bytes: Int): String = String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    }
}
