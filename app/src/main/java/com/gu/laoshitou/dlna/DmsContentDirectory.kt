// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import android.util.Log
import org.cybergarage.upnp.Action
import org.cybergarage.upnp.StateVariable
import org.cybergarage.upnp.control.ActionListener
import org.cybergarage.upnp.control.QueryListener

/**
 * ContentDirectory:1 browse model for the folder-based DMS library.
 *
 * Only BrowseDirectChildren/BrowseMetadata are implemented. Media URLs are
 * tokenized and always carry a source/MIME-derived suffix for older
 * renderers; the token itself is the only path component that identifies a
 * file.
 */
internal class DmsContentDirectory(
    private val library: DmsLibrary,
    private val tokenStore: MediaTokenStore,
    private val urlBaseProvider: () -> String?,
) : ActionListener, QueryListener {

    private val systemUpdateId = 1

    override fun actionControlReceived(action: Action): Boolean = when (action.name) {
        "Browse" -> browse(action)
        "Search" -> search(action)
        "GetSearchCapabilities" -> {
            action.setArgumentValue("SearchCaps", "")
            true
        }
        "GetSortCapabilities" -> {
            action.setArgumentValue("SortCaps", "")
            true
        }
        "GetSystemUpdateID" -> {
            action.setArgumentValue("Id", systemUpdateId)
            true
        }
        else -> {
            Log.w(TAG, "不支持的 ContentDirectory 动作: ${action.name}")
            action.setStatus(401, "Unsupported action")
            false
        }
    }

    override fun queryControlReceived(stateVar: StateVariable): Boolean {
        val value = when (stateVar.name) {
            "SystemUpdateID" -> systemUpdateId.toString()
            "ContainerUpdateIDs" -> ""
            "TransferIDs" -> ""
            else -> return false
        }
        stateVar.setValue(value)
        return true
    }

    private fun browse(action: Action): Boolean {
        val objectId = action.getArgumentValue("ObjectID").orEmpty().ifEmpty { ROOT_ID }
        val browseFlag = action.getArgumentValue("BrowseFlag").orEmpty()
        val start = action.getArgumentValue("StartingIndex")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val requested = action.getArgumentValue("RequestedCount")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        if (browseFlag == "BrowseMetadata") {
            val objectNode = metadata(objectId)
                ?: return fail(action, 701, "No such object")
            setBrowseResult(action, listOf(objectNode), 1, 1)
            return true
        }
        if (browseFlag.isNotEmpty() && browseFlag != "BrowseDirectChildren") {
            return fail(action, 402, "Unsupported BrowseFlag")
        }

        val all = children(objectId)
            ?: return fail(action, 701, "No such object")
        val page = if (requested <= 0) {
            all.drop(start)
        } else {
            all.drop(start).take(requested)
        }
        setBrowseResult(action, page, page.size, all.size)
        Log.i(TAG, "DMS Browse object=$objectId returned=${page.size}/${all.size}")
        return true
    }

    private fun search(action: Action): Boolean {
        // VLC and most renderers use Browse; return an empty, valid result
        // instead of failing discovery when a control point probes Search.
        setBrowseResult(action, emptyList(), 0, 0)
        return true
    }

    private fun setBrowseResult(
        action: Action,
        objects: List<DmsObject>,
        returned: Int,
        total: Int,
    ) {
        action.setArgumentValue("Result", buildDidl(objects))
        action.setArgumentValue("NumberReturned", returned)
        action.setArgumentValue("TotalMatches", total)
        action.setArgumentValue("UpdateID", systemUpdateId)
    }

    private fun children(objectId: String): List<DmsObject>? {
        if (objectId == ROOT_ID) {
            return library.albums().map { AlbumObject(it.id, it.title) }
        }
        library.findAlbum(objectId)?.let { album ->
            return library.tracks(album.id).map { TrackObject(it) }
        }
        if (library.findTrack(objectId) != null) {
            return emptyList()
        }
        return null
    }

    private fun metadata(objectId: String): DmsObject? {
        if (objectId == ROOT_ID) {
            return RootObject(library.rootTitle)
        }
        library.findAlbum(objectId)?.let { return AlbumObject(it.id, it.title) }
        library.findTrack(objectId)?.let { return TrackObject(it) }
        return null
    }

    private fun buildDidl(objects: List<DmsObject>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append(
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">"
        )
        for (obj in objects) {
            when (obj) {
                is RootObject -> appendContainer(
                    id = obj.id,
                    parentId = obj.parentId,
                    title = obj.title,
                    upnpClass = "object.container.storageFolder",
                    childCount = library.albums().size,
                )
                is AlbumObject -> appendContainer(
                    id = obj.id,
                    parentId = obj.parentId,
                    title = obj.title,
                    upnpClass = "object.container.album.musicAlbum",
                    childCount = library.tracks(obj.id).size,
                )
                is TrackObject -> appendTrack(obj.track)
            }
        }
        append("</DIDL-Lite>")
    }

    private fun StringBuilder.appendContainer(
        id: String,
        parentId: String,
        title: String,
        upnpClass: String,
        childCount: Int,
    ) {
        append("<container id=\"").append(escapeXml(id))
        append("\" parentID=\"").append(escapeXml(parentId))
        append("\" restricted=\"1\" searchable=\"0\" childCount=\"")
        append(childCount).append("\">")
        append("<dc:title>").append(escapeXml(title)).append("</dc:title>")
        append("<upnp:class>").append(upnpClass).append("</upnp:class>")
        append("</container>")
    }

    private fun StringBuilder.appendTrack(track: DmsTrack) {
        append("<item id=\"").append(escapeXml(track.id))
        append("\" parentID=\"").append(escapeXml(track.albumId))
        append("\" restricted=\"1\">")
        append("<dc:title>").append(escapeXml(track.title)).append("</dc:title>")
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        track.artist?.takeIf { it.isNotBlank() }?.let {
            append("<upnp:artist>").append(escapeXml(it)).append("</upnp:artist>")
        }
        track.album?.takeIf { it.isNotBlank() }?.let {
            append("<upnp:album>").append(escapeXml(it)).append("</upnp:album>")
        }
        val info = MediaProtocolInfo.forFileName(track.file.name)
        val url = mediaUrl(track, info)
        if (url != null) {
            append("<res protocolInfo=\"").append(protocolInfo(info)).append('"')
            append(" size=\"").append(track.file.length()).append('"')
            append('>').append(escapeXml(url)).append("</res>")
        }
        append("</item>")
    }

    private fun mediaUrl(track: DmsTrack, info: MediaProtocolInfo.Info?): String? {
        val base = urlBaseProvider()?.trimEnd('/') ?: return null
        val token = tokenStore.tokenFor(track)
        val fileName = MediaProtocolInfo.mediaFileName(track.id, track.file.name, info?.mime)
        return "$base${DmsMediaEndpoint.PREFIX}$token/$fileName"
    }

    private fun protocolInfo(info: MediaProtocolInfo.Info?): String {
        val mime = info?.mime ?: "application/octet-stream"
        return buildString {
            append("http-get:*:").append(mime).append(':')
            info?.dlnaPn?.let { append("DLNA.ORG_PN=").append(it).append(';') }
            append("DLNA.ORG_OP=01")
        }
    }

    private fun fail(action: Action, code: Int, message: String): Boolean {
        action.setStatus(code, message)
        return false
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

    private sealed interface DmsObject {
        val id: String
        val parentId: String
        val title: String
    }

    private data class RootObject(
        override val title: String,
    ) : DmsObject {
        override val id: String = ROOT_ID
        override val parentId: String = "-1"
    }

    private data class AlbumObject(
        override val id: String,
        override val title: String,
    ) : DmsObject {
        override val parentId: String = ROOT_ID
    }

    private data class TrackObject(
        val track: DmsTrack,
    ) : DmsObject {
        override val id: String = track.id
        override val parentId: String = track.albumId
        override val title: String = track.title
    }

    private companion object {
        const val TAG = "M0Dlna"
        const val ROOT_ID = "0"
    }
}
