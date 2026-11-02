// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.nas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebDavXmlTest {
    private fun fixture(name: String): String {
        val file = File("src/test/resources/$name")
        assertTrue("fixture missing: ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun parsesRealJayDepth1Response() {
        val entries = WebDavXml.parseMultiStatus(fixture("propfind-jay-depth1.xml"))
        assertTrue("expected several entries", entries.size > 10)
        val album = entries.first { it.isCollection && it.displayName == "Jay" }
        assertEquals("/musicok/%E5%91%A8%E6%9D%B0%E4%BC%A6/Jay/", album.href)
        val flac = entries.first { it.href.contains(".flac") }
        assertEquals(27_880_917L, flac.size)
        assertFalse(flac.isCollection)
        assertTrue(flac.displayName.orEmpty().contains("娘子"))
    }

    @Test
    fun parsesMusicokRoot() {
        val entries = WebDavXml.parseMultiStatus(fixture("propfind-musicok-depth1.xml"))
        assertTrue(entries.any { it.displayName == "musicok" && it.isCollection })
        assertTrue(entries.any { it.displayName == "周杰伦" && it.isCollection })
    }

    @Test
    fun decodesXmlEntities() {
        val entries = WebDavXml.parseMultiStatus(
            """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/musicok/a%20b/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>A &amp; B</D:displayname>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals("A & B", entries[0].displayName)
        assertTrue(entries[0].isCollection)
    }

    @Test
    fun ignoresPropertiesFrom404Propstat() {
        val entries = WebDavXml.parseMultiStatus(
            """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/x.flac</D:href>
                <D:propstat>
                  <D:prop><D:displayname>x.flac</D:displayname></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
                <D:propstat>
                  <D:prop><D:getcontentlength>123</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
            """.trimIndent(),
        )
        assertEquals(1, entries.size)
        assertEquals("x.flac", entries[0].displayName)
        assertNull(entries[0].size)
    }
}
