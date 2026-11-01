// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

internal object DmsDescription {

    fun build(udn: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"
              xmlns:dlna="urn:schemas-dlna-org:device-1-0">
          <specVersion>
            <major>1</major>
            <minor>0</minor>
          </specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>老石头 DMS</friendlyName>
            <manufacturer>LaoShiTou</manufacturer>
            <modelDescription>LaoShiTou DLNA media server</modelDescription>
            <modelName>LaoShiTou DMS</modelName>
            <modelNumber>0.1</modelNumber>
            <UDN>$udn</UDN>
            <dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                <SCPDURL>/dlna/contentdirectory/scpd.xml</SCPDURL>
                <controlURL>/dlna/contentdirectory/control</controlURL>
                <eventSubURL>/dlna/contentdirectory/event</eventSubURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <SCPDURL>/dlna/connectionmanager/scpd.xml</SCPDURL>
                <controlURL>/dlna/connectionmanager/control</controlURL>
                <eventSubURL>/dlna/connectionmanager/event</eventSubURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()
}
