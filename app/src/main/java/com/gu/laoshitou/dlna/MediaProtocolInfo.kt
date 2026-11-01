// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import java.net.URI
import java.net.URISyntaxException

/**
 * MIME/DLNA profile mapping and URL suffix policy.
 *
 * Several older renderers infer the format from the URL suffix rather than
 * the DIDL-Lite protocolInfo, so DMC/DMS media URLs must keep a correct
 * extension. The URL is generated from the source file name first, then MIME.
 */
internal object MediaProtocolInfo {

    data class Info(val mime: String, val dlnaPn: String?)

    private val byExtension = mapOf(
        "flac" to Info("audio/flac", "FLAC"),
        "wav" to Info("audio/wav", "LPCM"),
        "w64" to Info("audio/wav", "LPCM"),
        "mp3" to Info("audio/mpeg", "MP3"),
        "m4a" to Info("audio/mp4", "AAC_ISO"),
        "mp4" to Info("audio/mp4", "AAC_ISO"),
        "aac" to Info("audio/aac", "AAC_ADTS"),
        "ogg" to Info("audio/ogg", "OGG"),
        "oga" to Info("audio/ogg", "OGG"),
        "opus" to Info("audio/ogg", null),
        "ape" to Info("audio/x-ape", null),
        "wv" to Info("audio/x-wavpack", null),
        "tta" to Info("audio/x-tta", null),
        "dsf" to Info("audio/dsd", null),
        "dff" to Info("audio/dsd", null),
        "alac" to Info("audio/x-alac", null),
    )

    private val extensionByMime = buildMap {
        for ((extension, info) in byExtension) {
            putIfAbsent(info.mime.substringBefore(';').lowercase(), extension)
        }
        putIfAbsent("audio/x-wav", "wav")
        putIfAbsent("audio/x-alac", "m4a")
    }

    fun forFileName(fileName: String): Info? = extensionOf(fileName)?.let { byExtension[it] }

    fun forMime(mime: String?): Info? = extensionForMime(mime)?.let { byExtension[it] }

    fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.lastIndex) {
            return null
        }
        return fileName.substring(dot + 1).lowercase()
    }

    fun knownExtensionOf(fileName: String): String? =
        extensionOf(fileName)?.takeIf { byExtension.containsKey(it) }

    fun extensionForMime(mime: String?): String? {
        val normalized = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return extensionByMime[normalized]
    }

    /**
     * Appends the source/MIME extension to [uri] when the URL has no known
     * media suffix yet, preserving query and fragment. Returns [uri] when no
     * reliable extension is known or the URL already carries one.
     */
    fun ensureMediaSuffix(uri: URI, sourceFileName: String?, mime: String?): URI {
        val extension = knownExtensionOf(sourceFileName.orEmpty())
            ?: extensionForMime(mime)
            ?: return uri
        val rawPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: return uri
        if (rawPath.endsWith(".$extension", ignoreCase = true)) {
            return uri
        }
        if (knownExtensionOf(rawPath.substringAfterLast('/')) != null) {
            return uri
        }
        val authority = uri.rawAuthority ?: return uri
        val newPath = if (rawPath.endsWith('/')) {
            "${rawPath}media.$extension"
        } else {
            "$rawPath.$extension"
        }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return try {
            URI("${uri.scheme}://$authority$newPath$query$fragment")
        } catch (_: URISyntaxException) {
            uri
        }
    }

    /** File-name fragment for a DMS token URL, e.g. `42.flac`. */
    fun mediaFileName(id: String, sourceFileName: String?, mime: String?): String {
        val extension = knownExtensionOf(sourceFileName.orEmpty())
            ?: extensionForMime(mime)
            ?: return id
        return "$id.$extension"
    }
}
