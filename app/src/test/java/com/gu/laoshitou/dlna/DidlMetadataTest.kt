// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DidlMetadataTest {

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun buildsStandardAudioItemMetadata() {
        val title = "Amani & <Live> \"1991\""
        val mediaUrl = "http://192.168.1.9:39520/dlna/media/token/cast.flac?x=1&y=2"
        val xml = DmcClient.buildDidlMetadata(
            title = title,
            mediaUrl = mediaUrl,
            mime = "audio/flac",
            sizeBytes = 130_874_260L,
        )

        val document = parse(xml)
        val titleNode = document.getElementsByTagName("dc:title").item(0)
        val classNode = document.getElementsByTagName("upnp:class").item(0)
        val res = document.getElementsByTagName("res").item(0)

        assertEquals(title, titleNode.textContent)
        assertEquals("object.item.audioItem.musicTrack", classNode.textContent)
        assertEquals(mediaUrl, res.textContent)
        assertEquals("http-get:*:audio/flac:*", res.attributes.getNamedItem("protocolInfo").nodeValue)
        assertEquals("130874260", res.attributes.getNamedItem("size").nodeValue)
    }

    @Test
    fun omitsSizeWhenUnknown() {
        val xml = DmcClient.buildDidlMetadata(
            title = "Track",
            mediaUrl = "http://192.168.1.9:39520/dlna/media/token/cast.mp3",
            mime = "audio/mpeg",
        )
        val document = parse(xml)
        val res = document.getElementsByTagName("res").item(0)
        assertEquals("http-get:*:audio/mpeg:*", res.attributes.getNamedItem("protocolInfo").nodeValue)
        assertEquals(null, res.attributes.getNamedItem("size"))
        assertFalse(xml.contains("size="))
    }

    @Test
    fun escapesXmlSensitiveCharacters() {
        val xml = DmcClient.buildDidlMetadata(
            title = "a<b>c&d",
            mediaUrl = "http://192.168.1.9:39520/dlna/media/token/cast.flac",
            mime = "audio/flac",
        )
        assertFalse(xml.contains("a<b>"))
        assertNotNull(parse(xml).getElementsByTagName("dc:title").item(0))
    }
}
