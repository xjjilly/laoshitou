// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.content.Context
import android.util.Log
import org.cybergarage.xml.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Guarded DMC client. Discovery and every description fetch validate the
 * LOCATION/control URLs through [UrlGuard]; SOAP is sent only to the
 * validated AVTransport/RenderingControl control URLs.
 */
class DmcClient(private val context: Context) {

    data class Renderer(
        val location: URI,
        val friendlyName: String,
        val avControlUrl: URI,
        val renderingControlUrl: URI?,
        val connectionManagerUrl: URI?,
    )

    data class SoapResponse(val code: Int, val body: String) {
        val successful: Boolean get() = code in 200..299
    }

    fun findRenderer(timeoutMs: Long = 5_000): Renderer? =
        findRenderers(timeoutMs).firstOrNull()

    fun findRenderers(timeoutMs: Long = 5_000): List<Renderer> {
        val devices = GuardedDiscovery(context).discover(timeoutMs)
        val candidates = devices.filter {
            it.st.contains("MediaRenderer:1", ignoreCase = true) ||
                it.usn.contains("MediaRenderer:1", ignoreCase = true)
        }
        Log.i(TAG, "DMC 候选 MediaRenderer: ${candidates.size}")
        val result = LinkedHashMap<String, Renderer>()
        for (device in candidates) {
            try {
                val description = fetchDescription(device.location) ?: continue
                val renderer = parseRenderer(description, device.location) ?: continue
                result.putIfAbsent(renderer.location.toString(), renderer)
                Log.i(TAG, "DMC 发现 ${renderer.friendlyName} @ ${renderer.location}")
            } catch (e: Exception) {
                Log.w(TAG, "DMC 读取描述失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return result.values.toList()
    }

    fun setAvTransportUri(
        renderer: Renderer,
        mediaUrl: String,
        metadataXml: String? = null,
    ): SoapResponse {
        val args = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>" +
            "<CurrentURIMetaData>${escapeXml(metadataXml.orEmpty())}</CurrentURIMetaData>"
        return soap(renderer.avControlUrl, AVT_SERVICE_TYPE, "SetAVTransportURI", args)
    }

    fun play(renderer: Renderer): SoapResponse =
        soap(renderer.avControlUrl, AVT_SERVICE_TYPE, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun pause(renderer: Renderer): SoapResponse =
        soap(renderer.avControlUrl, AVT_SERVICE_TYPE, "Pause", "<InstanceID>0</InstanceID>")

    fun stop(renderer: Renderer): SoapResponse =
        soap(renderer.avControlUrl, AVT_SERVICE_TYPE, "Stop", "<InstanceID>0</InstanceID>")

    fun seek(renderer: Renderer, positionMs: Long): SoapResponse {
        val target = formatTime(positionMs)
        return soap(
            renderer.avControlUrl,
            AVT_SERVICE_TYPE,
            "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>",
        )
    }

    fun getTransportInfo(renderer: Renderer): SoapResponse =
        soap(renderer.avControlUrl, AVT_SERVICE_TYPE, "GetTransportInfo", "<InstanceID>0</InstanceID>")

    fun getPositionInfo(renderer: Renderer): SoapResponse =
        soap(renderer.avControlUrl, AVT_SERVICE_TYPE, "GetPositionInfo", "<InstanceID>0</InstanceID>")

    fun setVolume(renderer: Renderer, volume: Int): SoapResponse? {
        val control = renderer.renderingControlUrl ?: return null
        return soap(
            control,
            RC_SERVICE_TYPE,
            "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
        )
    }

    fun getVolume(renderer: Renderer): SoapResponse? {
        val control = renderer.renderingControlUrl ?: return null
        return soap(
            control,
            RC_SERVICE_TYPE,
            "GetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>",
        )
    }

    /** Sink protocol info, or null when the renderer has no ConnectionManager. */
    fun getSinkProtocolInfo(renderer: Renderer): SoapResponse? {
        val control = renderer.connectionManagerUrl ?: return null
        return soap(control, CM_SERVICE_TYPE, "GetProtocolInfo", "")
    }

    /** Whether the renderer's sink list mentions [mime]. */
    fun sinkSupportsMime(sinkProtocolInfo: String, mime: String): Boolean =
        sinkProtocolInfo.contains(mime, ignoreCase = true)

    private fun fetchDescription(location: URI): String? {
        val connection = openGuarded(location, "GET")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "DMC 描述 HTTP $code")
                return null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_DESCRIPTION_BYTES) {
                throw IOException("描述过大")
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRenderer(description: String, location: URI): Renderer? {
        val root = AndroidXmlParser().parse(ByteArrayInputStream(description.toByteArray(Charsets.UTF_8)))
        val device = root.getNode("device") ?: return null
        val friendlyName = device.getNodeValue("friendlyName").orEmpty()
        val serviceList = device.getNode("serviceList") ?: return null
        val base = root.getNodeValue("URLBase")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: location

        var avControl: URI? = null
        var rcControl: URI? = null
        var cmControl: URI? = null
        for (index in 0 until serviceList.getNNodes()) {
            val service: Node = serviceList.getNode(index)
            val serviceType = service.getNodeValue("serviceType")
            val controlText = service.getNodeValue("controlURL") ?: continue
            val control = try {
                UrlGuard.requireAllowed(base.resolve(controlText).toString())
            } catch (e: Exception) {
                Log.w(TAG, "DMC 拒绝 controlURL (${e.message})")
                continue
            }
            when (serviceType) {
                AVT_SERVICE_TYPE -> avControl = control
                RC_SERVICE_TYPE -> rcControl = control
                CM_SERVICE_TYPE -> cmControl = control
            }
        }
        val av = avControl ?: return null
        return Renderer(location, friendlyName, av, rcControl, cmControl)
    }

    private fun soap(
        controlUrl: URI,
        serviceType: String,
        action: String,
        arguments: String,
    ): SoapResponse {
        val envelope = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
            append(arguments)
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }
        val connection = (controlUrl.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        }
        return try {
            connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.i(TAG, "DMC SOAP $action -> HTTP $code")
            SoapResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun openGuarded(uri: URI, method: String): HttpURLConnection {
        var current = uri
        var redirects = 0
        while (true) {
            val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = method
                setRequestProperty("Accept-Encoding", "identity")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                if (redirects >= UrlGuard.MAX_REDIRECTS) {
                    connection.disconnect()
                    throw IOException("描述重定向次数过多")
                }
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("描述重定向缺少 Location")
                }
                current = UrlGuard.resolveRedirect(current, location.trim())
                redirects++
                continue
            }
            return connection
        }
    }

    companion object {
        private const val TAG = "M0Dlna"
        private const val AVT_SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RC_SERVICE_TYPE = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val CM_SERVICE_TYPE = "urn:schemas-upnp-org:service:ConnectionManager:1"
        private const val MAX_DESCRIPTION_BYTES = 1_048_576
        private const val DIDL_HEADER =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"

        /** True when the renderer runs on this phone (same LAN IPv4). */
        fun isLocalRenderer(renderer: Renderer, localAddress: String?): Boolean =
            !localAddress.isNullOrBlank() && renderer.location.host == localAddress

        /**
         * Builds the DIDL-Lite item that control points send as
         * `CurrentURIMetaData`. Many third-party renderers (TVs, AVRs,
         * BubbleUPnP, Windows Media Player) use the title and `res`
         * protocolInfo instead of guessing from the URL, so metadata is part
         * of the interop surface even though Kodi accepts an empty value.
         */
        fun buildDidlMetadata(
            title: String,
            mediaUrl: String,
            mime: String,
            sizeBytes: Long? = null,
        ): String = buildString {
            append(DIDL_HEADER)
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(escapeXml(title)).append("</dc:title>")
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("<res protocolInfo=\"http-get:*:")
            append(escapeXml(mime))
            append(":*\"")
            if (sizeBytes != null && sizeBytes > 0) {
                append(" size=\"").append(sizeBytes).append("\"")
            }
            append(">").append(escapeXml(mediaUrl)).append("</res>")
            append("</item></DIDL-Lite>")
        }

        private fun escapeXml(value: String): String = buildString(value.length) {
            for (character in value) {
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(character)
                }
            }
        }

        fun extractTag(xml: String, name: String): String? {
            val match = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)
            return match?.groupValues?.getOrNull(1)?.trim()
        }

        fun timeToMillis(value: String?): Long? {
            val parts = value?.split(':') ?: return null
            if (parts.size != 3) return null
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toLongOrNull() ?: return null
            if (minutes !in 0..59 || seconds !in 0..59) return null
            return ((hours * 60 + minutes) * 60 + seconds) * 1_000
        }

        fun formatTime(milliseconds: Long): String {
            val totalSeconds = (milliseconds.coerceAtLeast(0) / 1_000).toInt()
            val hours = totalSeconds / 3_600
            val minutes = (totalSeconds % 3_600) / 60
            val seconds = totalSeconds % 60
            return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
