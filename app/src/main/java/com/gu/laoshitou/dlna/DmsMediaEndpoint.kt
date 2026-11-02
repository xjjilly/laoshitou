// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.util.Log
import org.cybergarage.http.HTTPRequest
import org.cybergarage.http.HTTPResponse
import org.cybergarage.http.HTTPStatus
import java.io.IOException

/**
 * Token-protected media endpoint hosted by the CyberLink device HTTP server.
 *
 * CyberLink's HTTPRequest.post(HTTPResponse) already implements single-range
 * byte serving and HEAD handling; we only resolve the token, set headers and
 * provide the file stream. Unknown tokens never reveal a file path.
 */
internal class DmsMediaEndpoint(
    private val tokenStore: MediaTokenStore,
) {

    fun handle(request: HTTPRequest): Boolean {
        val uri = request.getURI() ?: return false
        if (!uri.startsWith(PREFIX)) {
            return false
        }
        if (!request.isGetRequest() && !request.isHeadRequest()) {
            reject(request, HTTPStatus.BAD_REQUEST)
            return true
        }
        val token = uri.removePrefix(PREFIX).substringBefore('/')
        if (token.isEmpty()) {
            reject(request, HTTPStatus.BAD_REQUEST)
            return true
        }
        val track = tokenStore.trackFor(token)
        if (track == null) {
            Log.w(TAG, "DMS 拒绝未知媒体 token")
            reject(request, HTTPStatus.BAD_REQUEST)
            return true
        }
        val resource = track.resource
        val total = resource.length
        if (total <= 0L) {
            reject(request, HTTPStatus.BAD_REQUEST)
            return true
        }

        val info = MediaProtocolInfo.forFileName(resource.name)
        val mime = info?.mime ?: resource.mime ?: "application/octet-stream"

        // CyberLink's getContentRange() expects "bytes start-end/total" with
        // spaces; a standard "bytes=start-end" Range header is parsed as 0-0
        // and would return the whole file. Normalize the request header to the
        // form its single-range engine understands before calling post().
        val rangeHeader = request.getHeaderValue("Range")
        if (rangeHeader.isNotBlank()) {
            val range = parseRange(rangeHeader, total)
            if (range == null) {
                // Clear the malformed Range header first; CyberLink's post()
                // would otherwise re-apply its broken 0-0 interpretation.
                request.clearHeaders()
                return request.returnResponse(HTTPStatus.INVALID_RANGE)
            }
            request.setHeader("Range", "bytes ${range.first}-${range.last}/$total")
            Log.i(TAG, "DMS Range: ${range.first}-${range.last}/$total ${resource.name}")
        }

        val response = HTTPResponse()
        response.setStatusCode(HTTPStatus.OK)
        response.setContentType(mime)
        response.setContentLength(total)
        response.setHeader("Accept-Ranges", "bytes")
        response.setHeader("transferMode.dlna.org", "Streaming")
        response.setHeader("contentFeatures.dlna.org", dlnaFeatures(info))

        val input = resource.openStream()
        response.setContentInputStream(input)
        return try {
            request.post(response)
            true
        } catch (e: IOException) {
            Log.w(TAG, "DMS 媒体发送失败: ${e.message}")
            false
        } finally {
            try {
                input.close()
            } catch (_: IOException) {
                // Response is already finished or the client disconnected.
            }
        }
    }

    private fun reject(request: HTTPRequest, status: Int): Boolean {
        request.clearHeaders()
        return request.returnResponse(status)
    }

    private fun dlnaFeatures(info: MediaProtocolInfo.Info?): String = buildString {
        info?.dlnaPn?.let { append("DLNA.ORG_PN=").append(it).append(';') }
        append("DLNA.ORG_OP=01;DLNA.ORG_CI=0")
    }

    /** Parses a single `bytes=` range; returns null for malformed/multi-range. */
    private fun parseRange(header: String, total: Long): LongRange? {
        if (!header.startsWith("bytes=", ignoreCase = true) || total <= 0) {
            return null
        }
        val spec = header.substringAfter('=').substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) {
            return null
        }
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0) {
                return null
            }
            return (total - suffix).coerceAtLeast(0) until total
        }
        val start = startText.toLongOrNull() ?: return null
        val end = if (endText.isEmpty()) total - 1 else endText.toLongOrNull() ?: return null
        if (start < 0 || start >= total || end < start) {
            return null
        }
        return start..minOf(end, total - 1)
    }

    companion object {
        const val PREFIX = "/dlna/media/"
        private const val TAG = "M0Dlna"
    }
}
