// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream

class DmsDescriptionTest {

    @Test
    fun descriptionParsesToDeviceNode() {
        val xml = DmsDescription.build("uuid:test-dms")
        val root = AndroidXmlParser().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val device = root.getNode("device")
        assertNotNull(device)
        assertEquals("urn:schemas-upnp-org:device:MediaServer:1", device?.getNodeValue("deviceType"))
        assertEquals("uuid:test-dms", device?.getNodeValue("UDN"))
        val serviceList = device?.getNode("serviceList")
        assertNotNull(serviceList)
        assertEquals(2, serviceList?.getNNodes())
    }
}
